package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code LDG06}: a line can only be added in the transaction that inserted its entry. The application's grant on
 * {@code entry_lines} covers any {@code journal_entry_id}, and the balance trigger only checks a sum, so without this
 * trigger two lines that cancel out could be added to an entry committed days ago, changing every balance as of any
 * time after its {@code created_at} (the as-of answer V5 exists to keep stable) and making the entry's reversal no
 * longer its exact negation.
 *
 * <p>These tests insert raw rows, as {@code ledger_app} and as the owner, and use real commits (no
 * {@code @Transactional} rollback): the refusal is at the insert, and what matters afterwards is what a later
 * transaction can see.
 */
@SpringBootTest
class EntryLinesWithTheirEntryTest extends AbstractIntegrationTest {

    private static final String LDG06 = "LDG06";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcJournalEntryAdapter adapter;

    private TransactionTemplate appTx;
    private long seller;
    private long fee;
    private long other;
    private long another;

    @BeforeEach
    void setUp() {
        appTx = new TransactionTemplate(transactionManager);
        seller = accountId(registerAccount("seller_payable", AccountType.LIABILITY));
        fee = accountId(registerAccount("acquirer_fee", AccountType.EXPENSE));
        other = accountId(registerAccount("marketplace_commission", AccountType.REVENUE));
        another = accountId(registerAccount("settlement_reserve", AccountType.ASSET));
    }

    private long accountId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, code);
    }

    private static void insertLine(JdbcTemplate jdbc, long entryId, int lineNumber, long accountId, long cents) {
        jdbc.update(
                "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, ?, ?, ?)",
                entryId, lineNumber, accountId, cents);
    }

    /** An entry committed by an earlier transaction: header and two lines (+1000 seller, -1000 fee). */
    private long committedEntry(String key) {
        return appTx.execute(status -> {
            long id = jdbcTemplate.queryForObject(
                    "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, key);
            insertLine(jdbcTemplate, id, 1, seller, 1000L);
            insertLine(jdbcTemplate, id, 2, fee, -1000L);
            return id;
        });
    }

    private int lineCountOf(long entryId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM entry_lines WHERE journal_entry_id = ?", Integer.class, entryId);
    }

    private long balanceOf(long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM entry_lines WHERE account_id = ?", Long.class, accountId);
    }

    private TransactionTemplate ownerTx() {
        return new TransactionTemplate(new DataSourceTransactionManager(ownerJdbc().getDataSource()));
    }

    // ------------------------------------------------------------------ the refusal

    // The review's reproduction: two lines that sum to zero into a committed entry, then the immediate balance check.
    @Test
    void aBalancedPairOfLinesCannotBeAddedToAnEntryCommittedEarlier() {
        long entryId = committedEntry(uniqueIdempotencyKey());

        Throwable thrown = catchThrowable(() -> appTx.executeWithoutResult(status -> {
            insertLine(jdbcTemplate, entryId, 3, other, 5000L);
            insertLine(jdbcTemplate, entryId, 4, another, -5000L);
            jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }));

        assertThat(thrown).as("the insert itself is refused, before any balance check").isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(LDG06);
        assertThat(lineCountOf(entryId)).as("the entry's lines are as they were").isEqualTo(2);
        assertThat(balanceOf(other)).isZero();
        assertThat(balanceOf(another)).isZero();
    }

    @Test
    void aSingleLineIsRefusedToo() {
        long entryId = committedEntry(uniqueIdempotencyKey());

        Throwable thrown = catchThrowable(
                () -> appTx.executeWithoutResult(status -> insertLine(jdbcTemplate, entryId, 3, other, 5000L)));

        assertThat(sqlStateOf(thrown)).isEqualTo(LDG06);
        assertThat(lineCountOf(entryId)).isEqualTo(2);
    }

    @Test
    void theRefusalNamesTheEntryAndSaysWhy() {
        long entryId = committedEntry(uniqueIdempotencyKey());

        Throwable thrown = catchThrowable(
                () -> appTx.executeWithoutResult(status -> insertLine(jdbcTemplate, entryId, 3, other, 5000L)));

        assertThat(serverMessageOf(thrown))
                .contains("entry_lines")
                .contains("transaction that inserted")
                .contains("journal_entry_id " + entryId);
    }

    @Test
    void theTableOwnerIsBoundToo() {
        long entryId = committedEntry(uniqueIdempotencyKey());
        JdbcTemplate owner = ownerJdbc();

        Throwable thrown = catchThrowable(
                () -> ownerTx().executeWithoutResult(status -> insertLine(owner, entryId, 3, other, 5000L)));

        assertThat(sqlStateOf(thrown)).isEqualTo(LDG06);
        assertThat(lineCountOf(entryId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ what must keep working

    @Test
    void positiveControl_anEntryAndItsLinesInsertedInOneTransactionCommit() {
        String key = uniqueIdempotencyKey();

        long entryId = committedEntry(key);

        assertThat(lineCountOf(entryId)).isEqualTo(2);
        assertThat(balanceOf(seller)).isEqualTo(1000L);
    }

    @Test
    void positiveControl_twoEntriesInOneTransactionEachTakeTheirOwnLines() {
        String keyA = uniqueIdempotencyKey();
        String keyB = uniqueIdempotencyKey();

        appTx.executeWithoutResult(status -> {
            long a = jdbcTemplate.queryForObject(
                    "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, keyA);
            long b = jdbcTemplate.queryForObject(
                    "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, keyB);
            insertLine(jdbcTemplate, a, 1, seller, 10L);
            insertLine(jdbcTemplate, b, 1, seller, 20L);
            insertLine(jdbcTemplate, a, 2, fee, -10L);
            insertLine(jdbcTemplate, b, 2, fee, -20L);
        });

        assertThat(balanceOf(seller)).isEqualTo(30L);
    }

    @Test
    void positiveControl_postingAndReversingThroughThePortStillWork() {
        List<EntryLine> lines = List.of(new EntryLine(1, accountCode(seller), 700L), new EntryLine(2, accountCode(fee), -700L));
        JournalEntry request = new JournalEntry(null, uniqueIdempotencyKey(), lines, null);

        JournalEntry original = ((PostOutcome.Created) adapter.insertOrFind(request)).entry();
        PostOutcome reversal = adapter.insertOrFind(JournalEntry.reversalOf(original, uniqueIdempotencyKey()));

        assertThat(reversal).isInstanceOf(PostOutcome.Created.class);
        assertThat(balanceOf(seller)).isZero();
        assertThat(balanceOf(fee)).isZero();
    }

    private String accountCode(long id) {
        return jdbcTemplate.queryForObject("SELECT code FROM accounts WHERE id = ?", String.class, id);
    }

    // The trigger only judges a header that exists; a missing one is the foreign key's to report, as it always was.
    @Test
    void aLineForAnEntryThatDoesNotExistIsStillAForeignKeyViolation() {
        Throwable thrown = catchThrowable(() -> appTx.executeWithoutResult(
                status -> insertLine(jdbcTemplate, 9_000_000_000L, 1, seller, 10L)));

        assertThat(sqlStateOf(thrown)).isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    // ------------------------------------------------------------------ the documented limit

    /**
     * A header inserted inside a savepoint carries the subtransaction's xid, which is not the transaction's own, so
     * its lines are refused after the savepoint is released. The application uses no savepoints; this pins the
     * limit the migration and docs/ledger-guarantees.md state, so that using one later fails here first.
     */
    @Test
    void limit_aHeaderInsertedInASavepointIsNotRecognisedByItsLinesAddedAfterTheSavepointIsReleased() {
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> appTx.executeWithoutResult(status -> {
            Object savepoint = status.createSavepoint();
            long id = jdbcTemplate.queryForObject(
                    "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, key);
            status.releaseSavepoint(savepoint);
            insertLine(jdbcTemplate, id, 1, seller, 10L);
        }));

        assertThat(sqlStateOf(thrown)).isEqualTo(LDG06);
    }

    /**
     * The limit is wider than "after the savepoint is released": pg_current_xact_id() is always the top-level xid, so
     * a header inserted inside a savepoint is not recognised even by lines inserted in that same savepoint.
     */
    @Test
    void limit_aHeaderAndItsLinesBothInsideTheSameSavepointAreRefusedToo() {
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> appTx.executeWithoutResult(status -> {
            Object savepoint = status.createSavepoint();
            long id = jdbcTemplate.queryForObject(
                    "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, key);
            insertLine(jdbcTemplate, id, 1, seller, 10L);
            status.releaseSavepoint(savepoint);
        }));

        assertThat(sqlStateOf(thrown)).isEqualTo(LDG06);
    }
}
