package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code insertOrFind} for a reversal, against a real database as {@code ledger_app}, with real commits.
 * The adapter reports state and enforces the constraint; it does not decide replay versus reuse.
 */
@SpringBootTest
class ReversalInsertOrFindTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcJournalEntryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String debit;
    private String credit;

    @BeforeEach
    void setUp() {
        debit = registerAccount("seller_payable", AccountType.LIABILITY);
        credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
    }

    private JournalEntry postOriginal() {
        JournalEntry request = new JournalEntry(
                null, uniqueIdempotencyKey(), List.of(new EntryLine(1, debit, 500L), new EntryLine(2, credit, -500L)), null);
        return ((PostOutcome.Created) adapter.insertOrFind(request)).entry();
    }

    private int headerCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }

    private int lineCount(String key) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM entry_lines el JOIN journal_entries je ON je.id = el.journal_entry_id
                WHERE je.idempotency_key = ?
                """,
                Integer.class,
                key);
    }

    @Test
    void aReversalIsCreatedWithItsLinkStoredAndItsLinesNegated() {
        JournalEntry original = postOriginal();
        String key = uniqueIdempotencyKey();

        PostOutcome outcome = adapter.insertOrFind(JournalEntry.reversalOf(original, key));

        assertThat(outcome).isInstanceOfSatisfying(PostOutcome.Created.class, created -> {
            JournalEntry reversal = created.entry();
            assertThat(reversal.entryId()).isNotNull().isNotEqualTo(original.entryId());
            assertThat(reversal.createdAt()).isNotNull();
            assertThat(reversal.idempotencyKey()).isEqualTo(key);
            assertThat(reversal.reversesEntryId()).isEqualTo(original.entryId());
            assertThat(reversal.lines())
                    .containsExactly(new EntryLine(1, debit, -500L), new EntryLine(2, credit, 500L));
            // The link is in the database, pointing at the original's internal id, and the original is untouched.
            assertThat(jdbcTemplate.queryForObject(
                            """
                            SELECT o.entry_id FROM journal_entries r JOIN journal_entries o ON o.id = r.reverses_entry_id
                            WHERE r.idempotency_key = ?
                            """,
                            UUID.class,
                            key))
                    .isEqualTo(original.entryId());
            assertThat(adapter.findByEntryId(original.entryId()).orElseThrow().reversesEntryId()).isNull();
        });
        assertThat(adapter.balanceOf(debit)).as("original and reversal cancel").isZero();
    }

    @Test
    void theSameKeyAndTargetAgainReportsTheExistingReversalNotAnException() {
        JournalEntry original = postOriginal();
        String key = uniqueIdempotencyKey();
        JournalEntry first = ((PostOutcome.Created) adapter.insertOrFind(JournalEntry.reversalOf(original, key))).entry();

        PostOutcome retry = adapter.insertOrFind(JournalEntry.reversalOf(original, key));

        assertThat(retry).isInstanceOfSatisfying(PostOutcome.AlreadyExists.class, existing -> {
            assertThat(existing.stored()).isEqualTo(first);
            assertThat(existing.stored().reversesEntryId()).isEqualTo(original.entryId());
        });
        assertThat(headerCount(key)).isEqualTo(1);
    }

    @Test
    void aDifferentKeyForTheSameTargetIsRefusedNamingTheOriginalAndLeavesNothingBehind() {
        JournalEntry original = postOriginal();
        adapter.insertOrFind(JournalEntry.reversalOf(original, uniqueIdempotencyKey()));
        String secondKey = uniqueIdempotencyKey();

        assertThatThrownBy(() -> adapter.insertOrFind(JournalEntry.reversalOf(original, secondKey)))
                .isInstanceOf(EntryAlreadyReversedException.class)
                .hasMessageContaining(original.entryId().toString());

        assertThat(headerCount(secondKey)).as("rolled back with the exception").isZero();
        assertThat(lineCount(secondKey)).isZero();
        // The rejected key was not consumed: it can still post an ordinary entry.
        JournalEntry ordinary = new JournalEntry(
                null, secondKey, List.of(new EntryLine(1, debit, 7L), new EntryLine(2, credit, -7L)), null);
        assertThat(adapter.insertOrFind(ordinary)).isInstanceOf(PostOutcome.Created.class);
    }

    // The failure the explicit lookup exists to prevent. Resolved by a scalar subquery, an unknown entry_id
    // would have inserted an ORDINARY entry (reverses_entry_id NULL) with the negated lines. It must be a
    // loud defect (a 500), and nothing at all may be written.
    @Test
    void aReversalOfAnEntryThatDoesNotExistIsADefectAndWritesNothing() {
        String key = uniqueIdempotencyKey();
        JournalEntry orphan = new JournalEntry(
                null, key, List.of(new EntryLine(1, debit, -500L), new EntryLine(2, credit, 500L)), null, UUID.randomUUID());

        assertThatThrownBy(() -> adapter.insertOrFind(orphan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(orphan.reversesEntryId().toString());

        assertThat(headerCount(key)).as("not even as an ordinary entry").isZero();
        assertThat(lineCount(key)).isZero();
    }

    @Test
    void anOrdinaryEntryIsStoredWithNoLink() {
        String key = uniqueIdempotencyKey();

        adapter.insertOrFind(new JournalEntry(
                null, key, List.of(new EntryLine(1, debit, 9L), new EntryLine(2, credit, -9L)), null));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT reverses_entry_id FROM journal_entries WHERE idempotency_key = ?", Long.class, key))
                .isNull();
    }

    // ------------------------------------------------------------------ only the at-most-once constraint is special

    // Real DuplicateKeyExceptions from the database, run through the very function the catch uses. The
    // at-most-once constraint is the only one reported as "already reversed"; every other unique
    // violation (here entry_lines_entry_account_uq) must keep propagating as the 500 it always was.
    @Test
    void onlyTheAtMostOnceConstraintIsClassifiedAsAlreadyReversed() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        JournalEntry original = postOriginal();
        adapter.insertOrFind(JournalEntry.reversalOf(original, uniqueIdempotencyKey()));
        long originalId = jdbcTemplate.queryForObject(
                "SELECT id FROM journal_entries WHERE entry_id = ?", Long.class, original.entryId());
        long accountId = jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, debit);

        Throwable secondReversal = catchThrowable(() -> tx.executeWithoutResult(status -> jdbcTemplate.update(
                "INSERT INTO journal_entries (idempotency_key, reverses_entry_id) VALUES (?, ?)",
                uniqueIdempotencyKey(), originalId)));
        Throwable repeatedAccount = catchThrowable(() -> tx.executeWithoutResult(status -> {
            long header = jdbcTemplate.queryForObject(
                    "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, uniqueIdempotencyKey());
            jdbcTemplate.update(
                    "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, 1, ?, 5)",
                    header, accountId);
            jdbcTemplate.update(
                    "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, 2, ?, -5)",
                    header, accountId);
        }));

        assertThat(secondReversal).isInstanceOf(DuplicateKeyException.class);
        assertThat(repeatedAccount).isInstanceOf(DuplicateKeyException.class);
        assertThat(JdbcJournalEntryAdapter.violatesAtMostOnce((DuplicateKeyException) secondReversal)).isTrue();
        assertThat(JdbcJournalEntryAdapter.violatesAtMostOnce((DuplicateKeyException) repeatedAccount)).isFalse();
    }
}
