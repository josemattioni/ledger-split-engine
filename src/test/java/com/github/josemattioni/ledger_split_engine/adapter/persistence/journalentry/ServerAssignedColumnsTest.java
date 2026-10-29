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
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ledger_app} may insert only the columns a client supplies (V5): an account's {@code code},
 * {@code owner_ref} and {@code type}; a journal entry's {@code idempotency_key} and {@code reverses_entry_id};
 * an entry line's {@code journal_entry_id}, {@code line_number}, {@code account_id} and {@code amount_cents}.
 * Everything else ({@code id}, {@code entry_id}, {@code created_at}, {@code currency}) comes from the column
 * defaults, so no row can be written with an invented identifier, currency or timestamp. Each test attempts one
 * column as {@code ledger_app} (the autowired {@link JdbcTemplate}, autocommit) and checks that nothing was written.
 *
 * <p>A generated identity has a second guard that runs earlier: naming an {@code id} without
 * {@code OVERRIDING SYSTEM VALUE} is refused for everyone, privilege or not, with {@code 428C9}. Only the
 * {@code OVERRIDING} form reaches the privilege check, so it is the one that proves the privilege is missing.
 */
@SpringBootTest
class ServerAssignedColumnsTest extends AbstractIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String GENERATED_ALWAYS = "428C9";

    /** Well above anything the identity sequences reach, so an explicit id never collides with a real row. */
    private static final AtomicLong EXPLICIT_IDS = new AtomicLong(2_000_000_000_000_000L);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JournalEntryPort journalEntryPort;

    private void assertRefused(String expectedSqlState, String sql, Object... args) {
        Throwable thrown = catchThrowable(() -> jdbcTemplate.update(sql, args));

        assertThat(thrown).as("statement must be refused: %s", sql).isNotNull();
        assertThat(sqlStateOf(thrown)).as("SQLSTATE for: %s", sql).isEqualTo(expectedSqlState);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /** A committed entry, so the line-level statements name rows that exist and the failure is the intended one. */
    private record Seeded(long headerId, long accountId, String code) {}

    private Seeded seedEntry() {
        String first = uniqueCode("seller_payable");
        String second = uniqueCode("acquirer_fee");
        accountPort.register(new Account(first, null, AccountType.LIABILITY, Currency.BRL, null));
        accountPort.register(new Account(second, null, AccountType.EXPENSE, Currency.BRL, null));
        String key = uniqueIdempotencyKey();
        journalEntryPort.insertOrFind(
                new JournalEntry(null, key, List.of(new EntryLine(1, first, 100L), new EntryLine(2, second, -100L)), null));
        long headerId = jdbcTemplate.queryForObject(
                "SELECT id FROM journal_entries WHERE idempotency_key = ?", Long.class, key);
        long accountId = jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, first);
        return new Seeded(headerId, accountId, first);
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void theApplicationCannotSupplyAnAccountsCurrency() {
        String code = uniqueCode("seller_payable");

        assertRefused(INSUFFICIENT_PRIVILEGE, "INSERT INTO accounts (code, type, currency) VALUES (?, 'ASSET', 'BRL')", code);

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE code = ?", code)).as("accounts written").isZero();
    }

    @Test
    void theApplicationCannotSupplyAnAccountsCreatedAt() {
        String code = uniqueCode("seller_payable");

        assertRefused(
                INSUFFICIENT_PRIVILEGE,
                "INSERT INTO accounts (code, type, created_at) VALUES (?, 'ASSET', now() - interval '1 day')",
                code);

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE code = ?", code)).as("accounts written").isZero();
    }

    @Test
    void theApplicationCannotOverrideAnAccountsGeneratedId() {
        long id = EXPLICIT_IDS.incrementAndGet();

        assertRefused(
                INSUFFICIENT_PRIVILEGE,
                "INSERT INTO accounts (id, code, type) OVERRIDING SYSTEM VALUE VALUES (?, ?, 'ASSET')",
                id,
                uniqueCode("seller_payable"));

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = ?", id)).as("accounts written").isZero();
    }

    @Test
    void namingAnAccountsIdWithoutOverridingIsRefusedByTheGeneratedIdentityItself() {
        long id = EXPLICIT_IDS.incrementAndGet();

        assertRefused(
                GENERATED_ALWAYS,
                "INSERT INTO accounts (id, code, type) VALUES (?, ?, 'ASSET')",
                id,
                uniqueCode("seller_payable"));

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = ?", id)).as("accounts written").isZero();
    }

    // ------------------------------------------------------------------ journal entries

    @Test
    void theApplicationCannotSupplyAJournalEntrysEntryId() {
        String key = uniqueIdempotencyKey();

        assertRefused(
                INSUFFICIENT_PRIVILEGE,
                "INSERT INTO journal_entries (idempotency_key, entry_id) VALUES (?, ?)",
                key,
                UUID.randomUUID());

        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", key))
                .as("headers written")
                .isZero();
    }

    @Test
    void theApplicationCannotSupplyAJournalEntrysCreatedAt() {
        String key = uniqueIdempotencyKey();

        assertRefused(
                INSUFFICIENT_PRIVILEGE,
                "INSERT INTO journal_entries (idempotency_key, created_at) VALUES (?, now() - interval '1 day')",
                key);

        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", key))
                .as("headers written")
                .isZero();
    }

    @Test
    void theApplicationCannotOverrideAJournalEntrysGeneratedId() {
        long id = EXPLICIT_IDS.incrementAndGet();
        String key = uniqueIdempotencyKey();

        assertRefused(
                INSUFFICIENT_PRIVILEGE,
                "INSERT INTO journal_entries (id, idempotency_key) OVERRIDING SYSTEM VALUE VALUES (?, ?)",
                id,
                key);

        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE id = ?", id)).as("headers written").isZero();
    }

    @Test
    void namingAJournalEntrysIdWithoutOverridingIsRefusedByTheGeneratedIdentityItself() {
        long id = EXPLICIT_IDS.incrementAndGet();

        assertRefused(
                GENERATED_ALWAYS,
                "INSERT INTO journal_entries (id, idempotency_key) VALUES (?, ?)",
                id,
                uniqueIdempotencyKey());

        assertThat(count("SELECT COUNT(*) FROM journal_entries WHERE id = ?", id)).as("headers written").isZero();
    }

    // ------------------------------------------------------------------ entry lines

    @Test
    void theApplicationCannotOverrideAnEntryLinesGeneratedId() {
        Seeded seeded = seedEntry();
        long id = EXPLICIT_IDS.incrementAndGet();

        assertRefused(
                INSUFFICIENT_PRIVILEGE,
                "INSERT INTO entry_lines (id, journal_entry_id, line_number, account_id, amount_cents)"
                        + " OVERRIDING SYSTEM VALUE VALUES (?, ?, 3, ?, 5)",
                id,
                seeded.headerId(),
                seeded.accountId());

        assertThat(count("SELECT COUNT(*) FROM entry_lines WHERE id = ?", id)).as("lines written").isZero();
    }

    @Test
    void namingAnEntryLinesIdWithoutOverridingIsRefusedByTheGeneratedIdentityItself() {
        Seeded seeded = seedEntry();
        long id = EXPLICIT_IDS.incrementAndGet();

        assertRefused(
                GENERATED_ALWAYS,
                "INSERT INTO entry_lines (id, journal_entry_id, line_number, account_id, amount_cents)"
                        + " VALUES (?, ?, 3, ?, 5)",
                id,
                seeded.headerId(),
                seeded.accountId());

        assertThat(count("SELECT COUNT(*) FROM entry_lines WHERE id = ?", id)).as("lines written").isZero();
    }

    // ------------------------------------------------------------------ the grants are exactly the client-supplied columns

    @Test
    void theInsertPrivilegesAreExactlyTheColumnsAClientSupplies() {
        Map<String, Set<String>> allColumns = new TreeMap<>();
        Map<String, Set<String>> insertable = new TreeMap<>();
        for (Map<String, Object> row : ownerJdbc().queryForList(
                """
                SELECT table_name, column_name, has_column_privilege(?, table_name, column_name, 'INSERT') AS can_insert
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name IN ('accounts', 'journal_entries', 'entry_lines')
                """,
                APP_USER)) {
            String table = (String) row.get("table_name");
            String column = (String) row.get("column_name");
            allColumns.computeIfAbsent(table, t -> new TreeSet<>()).add(column);
            if (Boolean.TRUE.equals(row.get("can_insert"))) {
                insertable.computeIfAbsent(table, t -> new TreeSet<>()).add(column);
            }
        }

        // Every column is accounted for: a column added later must be classified here, as granted or not.
        assertThat(allColumns)
                .containsEntry("accounts", Set.of("id", "code", "owner_ref", "type", "currency", "created_at"))
                .containsEntry("journal_entries", Set.of("id", "entry_id", "idempotency_key", "created_at", "reverses_entry_id"))
                .containsEntry("entry_lines", Set.of("id", "journal_entry_id", "line_number", "account_id", "amount_cents"));
        assertThat(insertable)
                .containsEntry("accounts", Set.of("code", "owner_ref", "type"))
                .containsEntry("journal_entries", Set.of("idempotency_key", "reverses_entry_id"))
                .containsEntry("entry_lines", Set.of("journal_entry_id", "line_number", "account_id", "amount_cents"));
    }

    @Test
    void noTableOfTheLedgerGrantsTheApplicationATableWideInsert() {
        for (String table : List.of("accounts", "journal_entries", "entry_lines")) {
            Boolean tableWide = ownerJdbc()
                    .queryForObject("SELECT has_table_privilege(?, ?, 'INSERT')", Boolean.class, APP_USER, table);
            assertThat(tableWide).as("table-wide INSERT on %s", table).isFalse();
        }
    }

    // ------------------------------------------------------------------ a later table needs its own column grant

    @Test
    void aLaterTableIsWritableOnlyThroughAnExplicitColumnLevelGrant() {
        String table = "zz_column_grant_probe";
        JdbcTemplate owner = ownerJdbc();
        owner.execute("CREATE TABLE " + table + " (a INT, b INT)");
        try {
            // Default privileges give SELECT and nothing else; the migration that creates the table grants columns.
            assertRefused(INSUFFICIENT_PRIVILEGE, "INSERT INTO " + table + " (a) VALUES (1)");
            owner.execute("GRANT INSERT (a) ON " + table + " TO " + APP_USER);

            assertThat(jdbcTemplate.update("INSERT INTO " + table + " (a) VALUES (1)")).as("the granted column").isEqualTo(1);
            assertRefused(INSUFFICIENT_PRIVILEGE, "INSERT INTO " + table + " (a, b) VALUES (1, 2)");
            assertThat(count("SELECT COUNT(*) FROM " + table)).as("rows, all through the granted column").isEqualTo(1);
        } finally {
            owner.execute("DROP TABLE " + table);
        }
    }

    // ------------------------------------------------------------------ positive controls: what the application does still works

    @Test
    void positiveControl_registeringAnAccountPostingAnEntryAndReversingItStillWork() {
        String first = uniqueCode("seller_payable");
        String second = uniqueCode("acquirer_fee");
        accountPort.register(new Account(first, null, AccountType.LIABILITY, Currency.BRL, null));
        accountPort.register(new Account(second, null, AccountType.EXPENSE, Currency.BRL, null));
        JournalEntry request = new JournalEntry(
                null,
                uniqueIdempotencyKey(),
                List.of(new EntryLine(1, first, 250L), new EntryLine(2, second, -250L)),
                null);

        JournalEntry original = ((PostOutcome.Created) journalEntryPort.insertOrFind(request)).entry();
        JournalEntry reversal = ((PostOutcome.Created)
                        journalEntryPort.insertOrFind(JournalEntry.reversalOf(original, uniqueIdempotencyKey())))
                .entry();

        assertThat(original.entryId()).as("a database-generated entry_id").isNotNull();
        assertThat(original.createdAt()).as("a database-generated created_at").isNotNull();
        assertThat(reversal.reversesEntryId()).as("the link column, which is granted").isEqualTo(original.entryId());
        assertThat(journalEntryPort.balanceOf(first)).isZero();
    }
}
