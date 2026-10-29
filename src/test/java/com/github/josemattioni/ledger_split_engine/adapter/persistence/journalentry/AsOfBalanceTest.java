package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code balanceOf(code, asOf)} and {@code databaseNow()} against a real database. Every entry is committed by
 * the table owner with an explicit, backdated {@code created_at} (the application role cannot supply one), so no
 * test waits for the settle window. "Now" comes from the database, as in production, truncated to the microsecond
 * Postgres stores.
 *
 * <p>Seeds on the first account ({@code seller}), each against {@code fee} with the opposite sign: +100 at
 * now - 10 min, +50 at now - 5 min, +25 at now - 2 min.
 */
@SpringBootTest
class AsOfBalanceTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcJournalEntryAdapter adapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String seller;
    private String fee;
    private Instant now;
    private Instant t1;
    private Instant t2;
    private Instant t3;

    @BeforeEach
    void seed() throws SQLException {
        seller = registerAccount("seller_payable", AccountType.LIABILITY);
        fee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        now = ownerJdbc().queryForObject("SELECT now()", Timestamp.class).toInstant().truncatedTo(ChronoUnit.MICROS);
        t1 = now.minus(Duration.ofMinutes(10));
        t2 = now.minus(Duration.ofMinutes(5));
        t3 = now.minus(Duration.ofMinutes(2));
        insert(t1, 100);
        insert(t2, 50);
        insert(t3, 25);
    }

    private SeededEntry insert(Instant createdAt, long sellerCents) throws SQLException {
        return insertEntryAsOwner(
                uniqueIdempotencyKey(),
                createdAt,
                null,
                List.of(new EntryLine(1, seller, sellerCents), new EntryLine(2, fee, -sellerCents)));
    }

    // ------------------------------------------------------------------ partial sums

    @Test
    void anInstantBetweenTheFirstTwoEntriesSeesOnlyTheFirst() {
        assertThat(adapter.balanceOf(seller, t1.plus(Duration.ofMinutes(1)))).isEqualTo(100);
        assertThat(adapter.balanceOf(fee, t1.plus(Duration.ofMinutes(1)))).isEqualTo(-100);
    }

    @Test
    void anInstantBetweenTheLastTwoEntriesSeesTheFirstTwo() {
        assertThat(adapter.balanceOf(seller, t2.plus(Duration.ofMinutes(1)))).isEqualTo(150);
    }

    @Test
    void anInstantAfterEveryEntrySeesAllOfThem() {
        assertThat(adapter.balanceOf(seller, now)).isEqualTo(175);
        assertThat(adapter.balanceOf(fee, now)).isEqualTo(-175);
    }

    @Test
    void theCurrentBalanceIsTheSumOfEveryEntry() {
        assertThat(adapter.balanceOf(seller)).isEqualTo(175);
    }

    @Test
    void anInstantEqualToAnEntrysCreatedAtIncludesThatEntry() {
        assertThat(adapter.balanceOf(seller, t2)).isEqualTo(150);
    }

    @Test
    void anInstantOneMicrosecondBeforeAnEntrysCreatedAtExcludesThatEntry() {
        assertThat(adapter.balanceOf(seller, t2.minus(1, ChronoUnit.MICROS))).isEqualTo(100);
    }

    @Test
    void anInstantBeforeEveryEntryGivesZero() {
        assertThat(adapter.balanceOf(seller, now.minus(Duration.ofHours(1)))).isZero();
    }

    @Test
    void anAccountWithNoLinesGivesZero() {
        String idle = registerAccount("settlement_hold", AccountType.ASSET);

        assertThat(adapter.balanceOf(idle, now)).isZero();
    }

    // ------------------------------------------------------------------ a reversal appears only from when it was recorded

    @Test
    void anOriginalAndItsLaterReversalShowTheOriginalBetweenThemAndZeroAfterBoth() throws SQLException {
        String a = registerAccount("seller_payable", AccountType.LIABILITY);
        String b = registerAccount("acquirer_fee", AccountType.EXPENSE);
        SeededEntry original = insertEntryAsOwner(
                uniqueIdempotencyKey(), t1, null, List.of(new EntryLine(1, a, 700), new EntryLine(2, b, -700)));
        insertEntryAsOwner(
                uniqueIdempotencyKey(), t2, original.entryId(), List.of(new EntryLine(1, a, -700), new EntryLine(2, b, 700)));

        assertThat(adapter.balanceOf(a, t1.plus(Duration.ofMinutes(1)))).as("between them: the original").isEqualTo(700);
        assertThat(adapter.balanceOf(a, t2.plus(Duration.ofMinutes(1)))).as("after both: cancelled").isZero();
    }

    @Test
    void entriesAreOrderedByCreatedAtNotById() throws SQLException {
        String a = registerAccount("seller_payable", AccountType.LIABILITY);
        String b = registerAccount("acquirer_fee", AccountType.EXPENSE);
        // The original gets an explicit, enormous id; its reversal gets the next generated one, which is lower.
        // Ordering by id would put the reversal before the original; ordering by created_at does not.
        SeededEntry original = insertEntryAsOwner(
                3_000_000_000_000_000L,
                uniqueIdempotencyKey(),
                t1,
                null,
                List.of(new EntryLine(1, a, 900), new EntryLine(2, b, -900)));
        SeededEntry reversal = insertEntryAsOwner(
                uniqueIdempotencyKey(), t2, original.entryId(), List.of(new EntryLine(1, a, -900), new EntryLine(2, b, 900)));
        assertThat(reversal.id()).as("the reversal really has the lower id").isLessThan(original.id());

        assertThat(adapter.balanceOf(a, t1.plus(Duration.ofMinutes(1))))
                .as("between them, by created_at: the original's effect")
                .isEqualTo(900);
        assertThat(adapter.balanceOf(a, t2)).as("at the reversal's created_at: cancelled").isZero();
    }

    // ------------------------------------------------------------------ the dependencies the settle argument needs

    @Test
    void theAsOfSumRefusesToRunInsideATransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> adapter.balanceOf(seller, now)))
                .as("a transaction that began earlier could hold a snapshot older than the clock read that settled the instant")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theDatabaseClockIsTheDatabasesOwnNow() {
        Instant before = Instant.now();
        Instant fromTheAdapter = adapter.databaseNow();
        Instant ownersView = ownerJdbc().queryForObject("SELECT now()", Timestamp.class).toInstant();

        assertThat(Duration.between(fromTheAdapter, ownersView).abs()).isLessThan(Duration.ofSeconds(5));
        assertThat(Duration.between(before, fromTheAdapter).abs()).isLessThan(Duration.ofSeconds(60));
    }
}
