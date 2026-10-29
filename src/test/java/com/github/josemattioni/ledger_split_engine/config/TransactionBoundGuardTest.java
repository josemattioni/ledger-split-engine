package com.github.josemattioni.ledger_split_engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The start-up guard against a real Postgres: the application's session ({@code ledger_app}, which the init
 * script bounds to 30 s) passes, the owner's session (which reads 0, not "absent") is refused with the
 * disabled-bound message and the {@code down -v} hint, and a server that allows prepared transactions is
 * refused naming that setting. The decision logic itself is unit-tested in {@code SettleWindowTest}.
 */
@SpringBootTest
class TransactionBoundGuardTest extends AbstractIntegrationTest {

    /** The application's datasource: connects as {@code ledger_app}. */
    @Autowired
    private JdbcTemplate applicationJdbc;

    // ------------------------------------------------------------------ the application's own session

    @Test
    void theApplicationsSessionReadsTheThirtySecondBoundAndNoPreparedTransactions() {
        TransactionBoundGuard.Settings settings = new TransactionBoundGuard(applicationJdbc).readSettings();

        assertThat(settings.transactionTimeoutMillis()).hasValue(30_000);
        assertThat(settings.maxPreparedTransactions()).hasValue(0);
    }

    @Test
    void aGuardOverTheApplicationsDatasourcePasses() {
        assertThatCode(() -> new TransactionBoundGuard(applicationJdbc).check()).doesNotThrowAnyException();
    }

    @Test
    void theConcurrencyTestsGiveUpWellBeforeTheServerEndsTheirTransactions() {
        long boundMillis = new TransactionBoundGuard(applicationJdbc)
                .readSettings()
                .transactionTimeoutMillis()
                .getAsLong();

        // A transaction in those tests can be open for a poll plus the wait after the other side lets go. This is
        // checked against the bound actually configured, not against the largest the guard accepts: the guard
        // allows any bound up to 30 s, and a lower one would still terminate a slow run before its own message.
        assertThat(CONCURRENCY_DEADLINE.multipliedBy(2).toMillis())
                .as("two concurrency deadlines against the configured transaction_timeout")
                .isLessThan(boundMillis);
    }

    // ------------------------------------------------------------------ the owner's session: no bound

    @Test
    void theOwnersSessionReadsZeroNotAbsent() {
        TransactionBoundGuard.Settings settings = new TransactionBoundGuard(ownerJdbc()).readSettings();

        assertThat(settings.transactionTimeoutMillis()).hasValue(0);
    }

    @Test
    void aGuardOverTheOwnersUnboundedSessionIsRefusedAsDisabledWithTheRecreateTheVolumeHint() {
        Throwable thrown = catchThrowable(() -> new TransactionBoundGuard(ownerJdbc()).check());

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction_timeout is 0")
                .hasMessageContaining("disabled")
                .hasMessageContaining("docker compose down -v")
                .hasMessageNotContaining("PostgreSQL 17");
    }

    // ------------------------------------------------------------------ prepared transactions

    @Test
    void aServerThatAllowsPreparedTransactionsIsRefusedNamingTheSettingEvenWithAGoodBound() {
        try (PostgreSQLContainer server =
                new PostgreSQLContainer("postgres:17").withCommand("postgres", "-c", "max_prepared_transactions=5")) {
            server.start();
            SingleConnectionDataSource dataSource =
                    new SingleConnectionDataSource(server.getJdbcUrl(), server.getUsername(), server.getPassword(), true);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                // A good bound on this one connection, so the prepared-transactions violation is the only one left.
                jdbc.execute("SET transaction_timeout = '30s'");

                Throwable thrown = catchThrowable(() -> new TransactionBoundGuard(jdbc).check());

                assertThat(thrown)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("max_prepared_transactions is 5")
                        .hasMessageNotContaining("transaction_timeout")
                        .hasMessageNotContaining("docker compose");
            } finally {
                dataSource.destroy();
            }
        }
    }
}
