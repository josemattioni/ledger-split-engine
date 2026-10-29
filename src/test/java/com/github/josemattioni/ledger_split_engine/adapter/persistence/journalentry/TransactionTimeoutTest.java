package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The bound the as-of balance depends on, shown against the real server: every transaction of
 * {@code ledger_app} ends after {@code transaction_timeout}, and what it wrote is never committed. The init
 * script sets 30 s on the role; these tests shorten it for one session ({@code SET}, which a role may do for
 * itself) so they do not wait 30 s. They are the deliberately time-based tests of the slice.
 *
 * <p>The {@code SET} is applied while the connection is still in autocommit, so it governs the transaction
 * that follows: a {@code SET} inside a transaction would not change that transaction's own timer.
 *
 * <p>Server-side, Postgres 17 ends such a session with {@code FATAL 25P04: terminating connection due to
 * transaction timeout} (observed for a transaction that is busy, idle, or waiting on a lock). pgjdbc surfaces
 * that FATAL as a {@link org.postgresql.util.PSQLException} carrying SQLSTATE {@code 25P04}: thrown by
 * {@code commit()} for an idle transaction, and inside the future's {@code ExecutionException} for a statement
 * that was waiting on a lock. Observed on every one of 25 repeated idle runs and 10 repeated lock-wait runs.
 */
@SpringBootTest
class TransactionTimeoutTest extends AbstractIntegrationTest {

    /** SQLSTATE of "terminating connection due to transaction timeout". */
    private static final String TRANSACTION_TIMEOUT = "25P04";

    @Test
    void theApplicationConnectionIsBoundedToThirtySeconds() throws Exception {
        try (Connection app = appConnection();
                PreparedStatement query =
                        app.prepareStatement("SELECT setting FROM pg_settings WHERE name = 'transaction_timeout'");
                ResultSet rs = query.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(30_000L);
        }
    }

    /**
     * The limit the docs state, demonstrated: {@code ALTER ROLE … SET transaction_timeout} sets a <em>default</em>
     * for the role's new sessions, and a session may change its own setting, so the bound is a default the
     * application keeps, not a restriction on the role. The as-of guarantee therefore assumes the application never
     * does this (it does not), and is no defence against someone who holds the application's credentials.
     */
    @Test
    void aSessionOfTheApplicationRoleCanSwitchItsOwnBoundOff() throws Exception {
        try (Connection app = appConnection();
                Statement statement = app.createStatement()) {
            statement.execute("SET transaction_timeout = 0");

            try (ResultSet rs = statement.executeQuery(
                    "SELECT setting FROM pg_settings WHERE name = 'transaction_timeout'")) {
                rs.next();
                assertThat(rs.getLong(1)).as("the session's own setting, after it switched the bound off").isZero();
            }
        }
        // And a new session of the role starts from the role's default again.
        try (Connection fresh = appConnection();
                Statement statement = fresh.createStatement();
                ResultSet rs = statement.executeQuery("SELECT setting FROM pg_settings WHERE name = 'transaction_timeout'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(30_000L);
        }
    }

    // ------------------------------------------------------------------ an idle transaction

    @Test
    void aTransactionLeftIdlePastItsBoundNeverCommits() throws Exception {
        String code = uniqueCode("timeout_idle");
        Throwable thrown;
        try (Connection app = appConnection()) {
            useBound(app, "1s");
            app.setAutoCommit(false);
            insertAccount(app, code);

            idleFor(Duration.ofMillis(1500));
            thrown = catchThrowable(app::commit);
        }

        assertThat(thrown).as("COMMIT after the transaction outlived its bound").isNotNull();
        assertThat(sqlStateOf(thrown)).as("how the driver reports the termination").isEqualTo(TRANSACTION_TIMEOUT);
        assertThat(accountCount(code)).as("the account written inside the terminated transaction").isZero();
    }

    // ------------------------------------------------------------------ a transaction waiting on a lock

    @Test
    void aStatementWaitingOnAnotherTransactionsLockIsTerminatedByItsBoundWhileTheOtherStillHoldsIt() throws Exception {
        String key = uniqueIdempotencyKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // B is declared first so it is closed last: closing the owner first rolls it back and releases B.
        try (Connection b = appConnection();
                Connection owner = ownerConnection();
                Connection observer = ownerConnection()) {
            owner.setAutoCommit(false);
            insertHeader(owner, key);
            int ownerPid = backendPid(owner);

            useBound(b, "2s");
            b.setAutoCommit(false);
            Future<List<Long>> bInsert = startBlocked(executor, b, () -> adapterConflictInsert(b, key));

            Throwable thrown = catchThrowable(() -> bInsert.get(CONCURRENCY_DEADLINE.toSeconds(), TimeUnit.SECONDS));

            assertThat(thrown)
                    .as("B's insert must be ended by its own bound, not still waiting")
                    .isInstanceOf(ExecutionException.class);
            assertThat(sqlStateOf(thrown)).as("how the driver reports the termination").isEqualTo(TRANSACTION_TIMEOUT);
            assertThat(stateOf(observer, ownerPid))
                    .as("the owner's transaction, which B was waiting for, is still open")
                    .isEqualTo("idle in transaction");

            owner.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(headerCount(key)).as("the owner rolled back and B never inserted").isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** Shortens this session's bound; run while the connection is in autocommit. */
    private static void useBound(Connection connection, String bound) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET transaction_timeout = '" + bound + "'");
        }
    }

    /** Waits without a fixed sleep, resuming early only if the deadline has passed. */
    private static void idleFor(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    private static void insertAccount(Connection connection, String code) throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO accounts (code, type) VALUES (?, 'ASSET')")) {
            insert.setString(1, code);
            insert.executeUpdate();
        }
    }

    private static void insertHeader(Connection connection, String key) throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO journal_entries (idempotency_key) VALUES (?)")) {
            insert.setString(1, key);
            insert.executeUpdate();
        }
    }

    /** The adapter's own header statement (JdbcJournalEntryAdapter.insertHeaderIfAbsent), run on B's connection. */
    private static List<Long> adapterConflictInsert(Connection connection, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO journal_entries (idempotency_key, reverses_entry_id)
                VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING
                RETURNING id, entry_id, created_at
                """)) {
            insert.setString(1, key);
            insert.setNull(2, Types.BIGINT);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = insert.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
    }

    private static String stateOf(Connection observer, int pid) throws SQLException {
        try (PreparedStatement query = observer.prepareStatement("SELECT state FROM pg_stat_activity WHERE pid = ?")) {
            query.setInt(1, pid);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getString(1) : "<no such backend>";
            }
        }
    }

    private static int accountCount(String code) {
        return ownerJdbc().queryForObject("SELECT COUNT(*) FROM accounts WHERE code = ?", Integer.class, code);
    }

    private static int headerCount(String key) {
        return ownerJdbc()
                .queryForObject("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }
}
