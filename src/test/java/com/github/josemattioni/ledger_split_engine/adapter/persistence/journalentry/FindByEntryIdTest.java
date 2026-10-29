package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Reading an entry back by its public {@code entry_id} (and by its key, which shares the query): lines
 * in {@code line_number} order, the stored key and {@code created_at}, and {@code reverses_entry_id} as
 * the original's <em>public</em> UUID — or null for an ordinary entry.
 *
 * <p>A reversal is inserted here by the table owner in one committed transaction, because writing
 * reversals through the adapter is a later step; the point of this class is the read.
 */
@SpringBootTest
class FindByEntryIdTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcJournalEntryAdapter adapter;

    private JournalEntry postOrdinary(String key, EntryLine... lines) {
        PostOutcome outcome = adapter.insertOrFind(new JournalEntry(null, key, List.of(lines), null));
        return ((PostOutcome.Created) outcome).entry();
    }

    /** Commits a reversal of {@code original} as the owner, with the original's lines negated. */
    private void insertReversalAsOwner(String reversalKey, JournalEntry original) throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            long reversalId;
            try (PreparedStatement header = connection.prepareStatement(
                    """
                    INSERT INTO journal_entries (idempotency_key, reverses_entry_id)
                    VALUES (?, (SELECT id FROM journal_entries WHERE entry_id = ?))
                    RETURNING id
                    """)) {
                header.setString(1, reversalKey);
                header.setObject(2, original.entryId());
                try (var rs = header.executeQuery()) {
                    rs.next();
                    reversalId = rs.getLong(1);
                }
            }
            try (PreparedStatement line = connection.prepareStatement(
                    """
                    INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents)
                    VALUES (?, ?, (SELECT id FROM accounts WHERE code = ?), ?)
                    """)) {
                for (EntryLine entryLine : original.lines()) {
                    line.setLong(1, reversalId);
                    line.setInt(2, entryLine.lineNumber());
                    line.setString(3, entryLine.accountCode());
                    line.setLong(4, -entryLine.amountCents());
                    line.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    @Test
    void anOrdinaryEntryComesBackWithItsKeyTimestampAndLinesInOrderAndNoLink() {
        String first = registerAccount("seller_payable", AccountType.LIABILITY);
        String second = registerAccount("marketplace_commission", AccountType.REVENUE);
        String third = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();
        JournalEntry posted =
                postOrdinary(key, new EntryLine(1, first, 9750L), new EntryLine(2, second, -750L), new EntryLine(3, third, -9000L));

        Optional<JournalEntry> found = adapter.findByEntryId(posted.entryId());

        assertThat(found).isPresent();
        assertThat(found.get().entryId()).isEqualTo(posted.entryId());
        assertThat(found.get().idempotencyKey()).isEqualTo(key);
        assertThat(found.get().createdAt()).isEqualTo(posted.createdAt());
        assertThat(found.get().lines()).containsExactlyElementsOf(posted.lines());
        assertThat(found.get().reversesEntryId()).isNull();
        assertThat(found.get().isReversal()).isFalse();
    }

    @Test
    void aReversalComesBackLinkedToTheOriginalsPublicEntryId() throws SQLException {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        JournalEntry original = postOrdinary(uniqueIdempotencyKey(), new EntryLine(1, debit, 500L), new EntryLine(2, credit, -500L));
        String reversalKey = uniqueIdempotencyKey();
        insertReversalAsOwner(reversalKey, original);

        JournalEntry reversal = adapter.findByEntryId(storedEntryIdOfKey(reversalKey)).orElseThrow();

        assertThat(reversal.reversesEntryId()).as("the original's public id, not an internal one").isEqualTo(original.entryId());
        assertThat(reversal.isReversal()).isTrue();
        assertThat(reversal.idempotencyKey()).isEqualTo(reversalKey);
        assertThat(reversal.lines())
                .containsExactly(new EntryLine(1, debit, -500L), new EntryLine(2, credit, 500L));
    }

    @Test
    void anEntryIdThatMatchesNoEntryIsEmpty() {
        assertThat(adapter.findByEntryId(UUID.randomUUID())).isEmpty();
    }

    // The key lookup shares the query: a stored reversal found because its key is taken carries its
    // link, which is what lets the use case tell a retry of it from a reuse of the key.
    @Test
    void aStoredReversalFoundByItsKeyCarriesItsLink() throws SQLException {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        JournalEntry original = postOrdinary(uniqueIdempotencyKey(), new EntryLine(1, debit, 500L), new EntryLine(2, credit, -500L));
        String reversalKey = uniqueIdempotencyKey();
        insertReversalAsOwner(reversalKey, original);

        // Any request under that key finds the stored entry first; only the key matters to the lookup.
        PostOutcome outcome = adapter.insertOrFind(
                new JournalEntry(null, reversalKey, List.of(new EntryLine(1, debit, 1L), new EntryLine(2, credit, -1L)), null));

        assertThat(outcome).isInstanceOfSatisfying(PostOutcome.AlreadyExists.class, existing -> {
            assertThat(existing.stored().reversesEntryId()).isEqualTo(original.entryId());
            assertThat(existing.stored().lines())
                    .containsExactly(new EntryLine(1, debit, -500L), new EntryLine(2, credit, 500L));
        });
    }

    private UUID storedEntryIdOfKey(String key) {
        return ownerJdbc().queryForObject("SELECT entry_id FROM journal_entries WHERE idempotency_key = ?", UUID.class, key);
    }
}
