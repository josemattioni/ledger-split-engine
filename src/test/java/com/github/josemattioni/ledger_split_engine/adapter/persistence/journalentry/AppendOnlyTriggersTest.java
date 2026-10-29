package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The second layer of the append-only guarantee: triggers that refuse UPDATE, DELETE and TRUNCATE on
 * {@code journal_entries} and {@code entry_lines} even for the table owner (which gets past the
 * grants). All statements here run as the owner through {@link #ownerJdbc()}, each in autocommit.
 *
 * <p>Row-level triggers only fire for rows that exist, so every test first commits a real entry (as
 * {@code ledger_app}, through the adapter) and then targets its rows.
 */
@SpringBootTest
class AppendOnlyTriggersTest extends AbstractIntegrationTest {

    private static final String LDG02 = "LDG02";

    @Autowired
    private JournalEntryPort journalEntryPort;

    private record Seeded(String firstAccountCode, String secondAccountCode, String idempotencyKey) {}

    private Seeded seedCommittedEntry() {
        String first = uniqueCode("seller_payable");
        String second = uniqueCode("acquirer_fee");
        accountPort.register(new Account(first, null, AccountType.LIABILITY, Currency.BRL, null));
        accountPort.register(new Account(second, null, AccountType.EXPENSE, Currency.BRL, null));
        String key = "key-" + UUID.randomUUID();
        journalEntryPort.insertOrFind(new JournalEntry(
                null, key, List.of(new EntryLine(1, first, 100L), new EntryLine(2, second, -100L)), null));
        return new Seeded(first, second, key);
    }

    private void assertRefusedAsAppendOnly(String sql, String operation, String table, Object... args) {
        Throwable thrown = catchThrowable(() -> ownerJdbc().update(sql, args));

        assertThat(thrown).as("statement must be refused: %s", sql).isNotNull();
        assertThat(sqlStateOf(thrown)).as("SQLSTATE for: %s", sql).isEqualTo(LDG02);
        assertThat(serverMessageOf(thrown))
                .contains(operation + " on " + table + " is not allowed")
                .contains("append-only");
    }

    /** Everything the seeded entry wrote is still there, unchanged. */
    private void assertSeedIntact(Seeded seeded) {
        JdbcTemplate owner = ownerJdbc();
        Integer headers = owner.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, seeded.idempotencyKey());
        List<Long> amounts = owner.queryForList(
                "SELECT amount_cents FROM entry_lines WHERE journal_entry_id ="
                        + " (SELECT id FROM journal_entries WHERE idempotency_key = ?) ORDER BY line_number",
                Long.class,
                seeded.idempotencyKey());

        assertThat(headers).isEqualTo(1);
        assertThat(amounts).containsExactly(100L, -100L);
        assertThat(journalEntryPort.balanceOf(seeded.firstAccountCode())).isEqualTo(100L);
        assertThat(journalEntryPort.balanceOf(seeded.secondAccountCode())).isEqualTo(-100L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal_entries", "entry_lines"})
    void ownerUpdateOfAnExistingRowIsRefused(String table) {
        Seeded seeded = seedCommittedEntry();
        String sql = table.equals("journal_entries")
                ? "UPDATE journal_entries SET created_at = now() WHERE idempotency_key = ?"
                : "UPDATE entry_lines SET amount_cents = amount_cents + 1 WHERE journal_entry_id ="
                        + " (SELECT id FROM journal_entries WHERE idempotency_key = ?)";

        assertRefusedAsAppendOnly(sql, "UPDATE", table, seeded.idempotencyKey());

        assertSeedIntact(seeded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal_entries", "entry_lines"})
    void ownerDeleteOfAnExistingRowIsRefused(String table) {
        Seeded seeded = seedCommittedEntry();
        String sql = table.equals("journal_entries")
                ? "DELETE FROM journal_entries WHERE idempotency_key = ?"
                : "DELETE FROM entry_lines WHERE journal_entry_id ="
                        + " (SELECT id FROM journal_entries WHERE idempotency_key = ?)";

        assertRefusedAsAppendOnly(sql, "DELETE", table, seeded.idempotencyKey());

        assertSeedIntact(seeded);
    }

    @Test
    void ownerTruncateOfEntryLinesIsRefused() {
        Seeded seeded = seedCommittedEntry();

        assertRefusedAsAppendOnly("TRUNCATE entry_lines", "TRUNCATE", "entry_lines");

        assertSeedIntact(seeded);
    }

    @Test
    void ownerTruncateOfBothTablesTogetherIsRefused() {
        Seeded seeded = seedCommittedEntry();

        // Both tables listed so the foreign-key check passes and the BEFORE TRUNCATE trigger fires.
        Throwable thrown = catchThrowable(() -> ownerJdbc().execute("TRUNCATE journal_entries, entry_lines"));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(LDG02);
        assertThat(serverMessageOf(thrown)).contains("is not allowed").contains("append-only");
        assertSeedIntact(seeded);
    }

    @Test
    void ownerTruncateOfJournalEntriesWithCascadeIsRefused() {
        Seeded seeded = seedCommittedEntry();

        Throwable thrown = catchThrowable(() -> ownerJdbc().execute("TRUNCATE journal_entries CASCADE"));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(LDG02);
        assertThat(serverMessageOf(thrown)).contains("append-only");
        assertSeedIntact(seeded);
    }

    @Test
    void aBareTruncateOfJournalEntriesIsStoppedByTheForeignKeyBeforeTheTriggerIsReached() {
        Seeded seeded = seedCommittedEntry();

        Throwable thrown = catchThrowable(() -> ownerJdbc().execute("TRUNCATE journal_entries"));

        // Documents design.md Decision 4: Postgres checks foreign-key references first (0A000), so
        // the bare form never gets as far as LDG02. The ledger is untouched either way.
        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo("0A000");
        assertSeedIntact(seeded);
    }

    @Test
    void positiveControl_theTriggersBlockOnlyMutationNotTheOwnersInserts() {
        Seeded seeded = seedCommittedEntry();
        JdbcTemplate owner = ownerJdbc();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        String ownerKey = "owner-key-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            long headerId = owner.queryForObject(
                    "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, ownerKey);
            insertLine(owner, headerId, 1, seeded.firstAccountCode(), 50L);
            insertLine(owner, headerId, 2, seeded.secondAccountCode(), -50L);
        });

        Integer lines = owner.queryForObject(
                "SELECT COUNT(*) FROM entry_lines WHERE journal_entry_id ="
                        + " (SELECT id FROM journal_entries WHERE idempotency_key = ?)",
                Integer.class,
                ownerKey);
        assertThat(lines).isEqualTo(2);
    }

    private static void insertLine(JdbcTemplate owner, long headerId, int lineNumber, String accountCode, long cents) {
        owner.update(
                "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents)"
                        + " VALUES (?, ?, (SELECT id FROM accounts WHERE code = ?), ?)",
                headerId,
                lineNumber,
                accountCode,
                cents);
    }
}
