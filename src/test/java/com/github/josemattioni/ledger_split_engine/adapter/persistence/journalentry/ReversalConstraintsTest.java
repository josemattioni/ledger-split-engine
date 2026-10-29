package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database backstops behind the domain's reversal rules (V4): the foreign key, the at-most-once
 * {@code UNIQUE}, and the deferred trigger that raises {@code LDG04} (a reversal's lines are not the
 * exact negation of its original's) and {@code LDG05} (its original is itself a reversal, which also
 * covers a reversal that references itself and a cyclic link).
 *
 * <p>The domain builds reversals correctly, so none of this is reachable through the API: these tests
 * insert raw rows as {@code ledger_app} through {@link JdbcTemplate}, bypassing the domain on purpose. The two
 * that need an explicit {@code id} (a row that reverses itself, two rows that reverse each other) run as the
 * table owner instead: since V5 {@code ledger_app} cannot supply an id, even with {@code OVERRIDING SYSTEM
 * VALUE}. Their expected failures are unchanged.
 * Like the other deferred-trigger tests they use real commits ({@link TransactionTemplate}), never
 * {@code @Transactional} rollback isolation, because the deferred checks only run at {@code COMMIT}.
 *
 * <p>Every fixture has two or more balanced lines on every row, so exactly one rule can fail: a row
 * with no lines would report {@code LDG03} first ({@code trg_journal_entries_min_lines} sorts before
 * the reversal trigger), and an unbalanced one would add {@code LDG01}.
 */
@SpringBootTest
class ReversalConstraintsTest extends AbstractIntegrationTest {

    private static final String LDG02 = "LDG02";
    private static final String LDG04 = "LDG04";
    private static final String LDG05 = "LDG05";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /** Well above anything the identity sequence will reach, so explicit ids never collide with it. */
    private static final AtomicLong EXPLICIT_IDS = new AtomicLong(1_000_000_000_000_000L);

    private record Line(long accountId, long amountCents) {}

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;
    private long a1;
    private long a2;
    private long a3;
    private long a4;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        a1 = accountId("seller_payable", AccountType.LIABILITY);
        a2 = accountId("acquirer_fee", AccountType.EXPENSE);
        a3 = accountId("marketplace_commission", AccountType.REVENUE);
        a4 = accountId("settlement_hold", AccountType.ASSET);
    }

    private long accountId(String prefix, AccountType type) {
        String code = registerAccount(prefix, type);
        return jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, code);
    }

    // ------------------------------------------------------------------ raw inserts

    private long insertOrdinaryHeader(String key) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, key);
    }

    private long insertReversalHeader(String key, long reversesEntryId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO journal_entries (idempotency_key, reverses_entry_id) VALUES (?, ?) RETURNING id",
                Long.class,
                key,
                reversesEntryId);
    }

    private void insertLines(long headerId, Line... lines) {
        for (int i = 0; i < lines.length; i++) {
            insertLine(headerId, i + 1, lines[i].accountId(), lines[i].amountCents());
        }
    }

    private void insertLine(long headerId, int lineNumber, long accountId, long amountCents) {
        insertLine(jdbcTemplate, headerId, lineNumber, accountId, amountCents);
    }

    private static void insertLine(JdbcTemplate jdbc, long headerId, int lineNumber, long accountId, long amountCents) {
        jdbc.update(
                "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, ?, ?, ?)",
                headerId, lineNumber, accountId, amountCents);
    }

    /** One owner connection and a transaction over it, for the tests that must supply an explicit id. */
    private record OwnerTransaction(JdbcTemplate jdbc, TransactionTemplate tx) {
        static OwnerTransaction open() {
            JdbcTemplate owner = ownerJdbc();
            return new OwnerTransaction(owner, new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())));
        }

        void insertLines(long headerId, Line... lines) {
            for (int i = 0; i < lines.length; i++) {
                insertLine(jdbc, headerId, i + 1, lines[i].accountId(), lines[i].amountCents());
            }
        }
    }

    /** Commits an ordinary entry and returns its internal id. */
    private long commitOriginal(Line... lines) {
        return txTemplate.execute(status -> {
            long id = insertOrdinaryHeader(uniqueIdempotencyKey());
            insertLines(id, lines);
            return id;
        });
    }

    /** Commits a reversal of {@code originalId} with exactly these lines and returns its internal id. */
    private long commitReversal(long originalId, Line... lines) {
        return txTemplate.execute(status -> {
            long id = insertReversalHeader(uniqueIdempotencyKey(), originalId);
            insertLines(id, lines);
            return id;
        });
    }

    /** Tries to commit a reversal of {@code originalId} with these lines; returns what was thrown, or null. */
    private Throwable tryReversal(long originalId, String key, Line... lines) {
        return catchThrowable(() -> txTemplate.executeWithoutResult(status -> {
            long id = insertReversalHeader(key, originalId);
            insertLines(id, lines);
        }));
    }

    private int headerCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }

    private Long linkOf(long headerId) {
        return jdbcTemplate.queryForObject(
                "SELECT reverses_entry_id FROM journal_entries WHERE id = ?", Long.class, headerId);
    }

    private List<Long> amountsOf(long headerId) {
        return jdbcTemplate.queryForList(
                "SELECT amount_cents FROM entry_lines WHERE journal_entry_id = ? ORDER BY line_number",
                Long.class,
                headerId);
    }

    private void assertCommitFailedWith(Throwable thrown, String sqlState) {
        assertThat(thrown).as("the commit must fail").isInstanceOf(TransactionSystemException.class);
        assertThat(sqlStateOf(thrown)).isEqualTo(sqlState);
    }

    // ------------------------------------------------------------------ the positive control

    @Test
    void positiveControl_aProperReversalCommitsAndLeavesTheOriginalUntouched() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));

        long reversal = commitReversal(original, new Line(a1, -100L), new Line(a2, 100L));

        assertThat(linkOf(reversal)).as("the reversal points at its original").isEqualTo(original);
        assertThat(amountsOf(reversal)).containsExactly(-100L, 100L);
        assertThat(linkOf(original)).as("the original's own link is untouched").isNull();
        assertThat(amountsOf(original)).containsExactly(100L, -100L);
    }

    @Test
    void anOrdinaryEntryWithTheSameLinesAsAnotherCommitsBecauseTheTriggerOnlyWatchesReversals() {
        commitOriginal(new Line(a1, 100L), new Line(a2, -100L));

        long another = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));

        assertThat(linkOf(another)).isNull();
    }

    // ------------------------------------------------------------------ LDG04: not the exact negation

    @Test
    void aReversalWhoseLinesAreNotNegatedFailsAtCommitWithLdg04() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));
        String key = uniqueIdempotencyKey();

        // The same lines as the original: it balances, so only the negation rule can object.
        Throwable thrown = tryReversal(original, key, new Line(a1, 100L), new Line(a2, -100L));

        assertCommitFailedWith(thrown, LDG04);
        assertThat(headerCount(key)).as("nothing committed").isZero();
    }

    @Test
    void aBalancedButWrongThreeLineReversalFailsAtCommitWithLdg04() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -60L), new Line(a3, -40L));
        String key = uniqueIdempotencyKey();

        // -100, +40, +60 balances, but the negation is -100, +60, +40: the balance trigger cannot see this.
        Throwable thrown = tryReversal(original, key, new Line(a1, -100L), new Line(a2, 40L), new Line(a3, 60L));

        assertCommitFailedWith(thrown, LDG04);
        assertThat(headerCount(key)).isZero();
    }

    @Test
    void aReversalMissingLinesFailsAtCommitWithLdg04() {
        long original = commitOriginal(
                new Line(a1, 100L), new Line(a2, -100L), new Line(a3, 50L), new Line(a4, -50L));
        String key = uniqueIdempotencyKey();

        // Lines 1 and 2 are exactly right and balance on their own; lines 3 and 4 are missing.
        Throwable thrown = tryReversal(original, key, new Line(a1, -100L), new Line(a2, 100L));

        assertCommitFailedWith(thrown, LDG04);
        assertThat(headerCount(key)).isZero();
    }

    @Test
    void aReversalWithExtraLinesFailsAtCommitWithLdg04() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));
        String key = uniqueIdempotencyKey();

        // Lines 1 and 2 are exactly right; lines 3 and 4 are extra and balance each other.
        Throwable thrown = tryReversal(
                original, key, new Line(a1, -100L), new Line(a2, 100L), new Line(a3, 50L), new Line(a4, -50L));

        assertCommitFailedWith(thrown, LDG04);
        assertThat(headerCount(key)).isZero();
    }

    @Test
    void aReversalWithTheRightAmountsUnderSwappedLineNumbersFailsAtCommitWithLdg04() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));
        String key = uniqueIdempotencyKey();

        // Each account's amount is negated correctly; only the line numbers are exchanged.
        Throwable thrown = tryReversal(original, key, new Line(a2, 100L), new Line(a1, -100L));

        assertCommitFailedWith(thrown, LDG04);
        assertThat(headerCount(key)).isZero();
    }

    // ------------------------------------------------------------------ LDG05: the original is a reversal

    @Test
    void aReversalOfAReversalFailsAtCommitWithLdg05() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));
        long firstReversal = commitReversal(original, new Line(a1, -100L), new Line(a2, 100L));
        String key = uniqueIdempotencyKey();

        // A <- B <- C: C reverses B. Its lines are exactly the negation of B's (the original movement),
        // so nothing but the chain is wrong.
        Throwable thrown = tryReversal(firstReversal, key, new Line(a1, 100L), new Line(a2, -100L));

        assertCommitFailedWith(thrown, LDG05);
        assertThat(headerCount(key)).isZero();
    }

    @Test
    void aReversalThatReferencesItselfFailsAtCommitWithLdg05() {
        long id = EXPLICIT_IDS.incrementAndGet();
        String key = uniqueIdempotencyKey();

        OwnerTransaction owner = OwnerTransaction.open();

        Throwable thrown = catchThrowable(() -> owner.tx().executeWithoutResult(status -> {
            owner.jdbc().update(
                    "INSERT INTO journal_entries (id, idempotency_key, reverses_entry_id)"
                            + " OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)",
                    id, key, id);
            owner.insertLines(id, new Line(a1, 100L), new Line(a2, -100L));
        }));

        assertCommitFailedWith(thrown, LDG05);
        assertThat(serverMessageOf(thrown)).contains("journal entry " + id + " reverses entry " + id);
        assertThat(headerCount(key)).isZero();
    }

    // Two rows that reverse each other, in ONE multi-row INSERT with explicit ids. Each row's "original"
    // is a reversal, so the trigger objects; the deferred events fire in VALUES order, so the first row
    // of the list is the one that reports it (observed against Postgres 16 with the list in either order).
    @ParameterizedTest(name = "[{index}] first row in the VALUES list is {0}")
    @ValueSource(strings = {"X", "Y"})
    void twoRowsThatReverseEachOtherInOneInsertFailAtCommitWithLdg05ReportedByTheFirstRow(String first) {
        long x = EXPLICIT_IDS.incrementAndGet();
        long y = EXPLICIT_IDS.incrementAndGet();
        long firstId = first.equals("X") ? x : y;
        long secondId = first.equals("X") ? y : x;
        String firstKey = uniqueIdempotencyKey();
        String secondKey = uniqueIdempotencyKey();

        OwnerTransaction owner = OwnerTransaction.open();

        Throwable thrown = catchThrowable(() -> owner.tx().executeWithoutResult(status -> {
            owner.jdbc().update(
                    "INSERT INTO journal_entries (id, idempotency_key, reverses_entry_id)"
                            + " OVERRIDING SYSTEM VALUE VALUES (?, ?, ?), (?, ?, ?)",
                    firstId, firstKey, secondId, secondId, secondKey, firstId);
            // Each row gets two lines, the second row's the exact negation of the first's.
            owner.insertLines(firstId, new Line(a1, 100L), new Line(a2, -100L));
            owner.insertLines(secondId, new Line(a1, -100L), new Line(a2, 100L));
        }));

        assertCommitFailedWith(thrown, LDG05);
        assertThat(serverMessageOf(thrown)).contains("journal entry " + firstId + " reverses entry " + secondId);
        assertThat(headerCount(firstKey) + headerCount(secondKey)).as("nothing committed").isZero();
    }

    // ------------------------------------------------------------------ the foreign key and the UNIQUE

    @Test
    void aSecondReversalOfTheSameEntryUnderANewKeyFailsAtInsertOnTheUniqueConstraint() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));
        commitReversal(original, new Line(a1, -100L), new Line(a2, 100L));
        String key = uniqueIdempotencyKey();

        Throwable thrown = tryReversal(original, key, new Line(a1, -100L), new Line(a2, 100L));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(UNIQUE_VIOLATION);
        assertThat(constraintOf(thrown)).isEqualTo("journal_entries_reverses_entry_uq");
        assertThat(headerCount(key)).as("nothing committed").isZero();
    }

    @Test
    void aReversalOfAnEntryThatDoesNotExistFailsAtInsertOnTheForeignKey() {
        long missing = EXPLICIT_IDS.incrementAndGet();
        String key = uniqueIdempotencyKey();

        Throwable thrown = tryReversal(missing, key, new Line(a1, -100L), new Line(a2, 100L));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(FOREIGN_KEY_VIOLATION);
        assertThat(constraintOf(thrown)).isEqualTo("journal_entries_reverses_entry_fk");
        assertThat(headerCount(key)).isZero();
    }

    // ------------------------------------------------------------------ the link is never added after the fact

    @Test
    void theOwnerCannotAddALinkToAnExistingEntryBecauseTheLedgerIsAppendOnly() {
        long original = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));
        long ordinary = commitOriginal(new Line(a1, 100L), new Line(a2, -100L));

        Throwable thrown = catchThrowable(() -> ownerJdbc()
                .update("UPDATE journal_entries SET reverses_entry_id = ? WHERE id = ?", original, ordinary));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(LDG02);
        assertThat(serverMessageOf(thrown)).contains("UPDATE on journal_entries is not allowed");
        assertThat(linkOf(ordinary)).as("still not a reversal").isNull();
    }
}
