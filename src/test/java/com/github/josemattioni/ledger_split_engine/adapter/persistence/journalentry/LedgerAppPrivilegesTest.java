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

/**
 * The application connects as {@code ledger_app}, which holds SELECT, and INSERT only on the columns a client
 * supplies (V3 granted the whole table, V5 narrowed it; see {@code ServerAssignedColumnsTest}). The
 * autowired {@link JdbcTemplate} is that role; {@link #ownerJdbc()} is the table owner. Every
 * statement runs in autocommit, so each is its own real transaction.
 *
 * <p>The denial tests target rows that exist (seeded by a real committed post) so a permission
 * failure can't be confused with "nothing to update".
 */
@SpringBootTest
class LedgerAppPrivilegesTest extends AbstractIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JournalEntryPort journalEntryPort;

    private record Seeded(String firstAccountCode, String secondAccountCode, String idempotencyKey) {}

    /** Registers two accounts and commits a balanced two-line entry between them (+100 / -100). */
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

    private void assertSeedIntact(Seeded seeded) {
        assertThat(accountPort.findByCode(seeded.firstAccountCode())).isPresent();
        assertThat(accountPort.findByCode(seeded.secondAccountCode())).isPresent();
        assertThat(journalEntryPort.balanceOf(seeded.firstAccountCode())).isEqualTo(100L);
        assertThat(journalEntryPort.balanceOf(seeded.secondAccountCode())).isEqualTo(-100L);
    }

    private void assertDeniedWithInsufficientPrivilege(String sql, Object... args) {
        Throwable thrown = catchThrowable(() -> jdbcTemplate.update(sql, args));
        assertThat(thrown).as("statement must be refused: %s", sql).isNotNull();
        assertThat(sqlStateOf(thrown)).as("SQLSTATE for: %s", sql).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // ------------------------------------------------------------------ who connects as whom

    @Test
    void theApplicationDatasourceConnectsAsLedgerApp() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_USER);
    }

    @Test
    void migrationsWereAppliedByTheOwnerNotTheApplicationRole() {
        String owner = ownerJdbc().queryForObject("SELECT current_user", String.class);

        List<String> installers =
                ownerJdbc().queryForList("SELECT DISTINCT installed_by FROM flyway_schema_history", String.class);

        assertThat(owner).isNotEqualTo(APP_USER);
        assertThat(installers).containsExactly(owner);
    }

    // ------------------------------------------------------------------ SELECT and INSERT only

    @ParameterizedTest
    @ValueSource(strings = {"accounts", "journal_entries", "entry_lines"})
    void updateIsRefusedWithInsufficientPrivilege(String table) {
        Seeded seeded = seedCommittedEntry();
        String sql;
        Object arg;
        switch (table) {
            case "accounts" -> {
                sql = "UPDATE accounts SET owner_ref = 'tampered' WHERE code = ?";
                arg = seeded.firstAccountCode();
            }
            case "journal_entries" -> {
                sql = "UPDATE journal_entries SET created_at = now() WHERE idempotency_key = ?";
                arg = seeded.idempotencyKey();
            }
            default -> {
                sql = "UPDATE entry_lines SET amount_cents = amount_cents + 1 WHERE journal_entry_id ="
                        + " (SELECT id FROM journal_entries WHERE idempotency_key = ?)";
                arg = seeded.idempotencyKey();
            }
        }

        assertDeniedWithInsufficientPrivilege(sql, arg);

        assertSeedIntact(seeded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accounts", "journal_entries", "entry_lines"})
    void deleteIsRefusedWithInsufficientPrivilege(String table) {
        Seeded seeded = seedCommittedEntry();
        String sql;
        Object arg;
        switch (table) {
            case "accounts" -> {
                sql = "DELETE FROM accounts WHERE code = ?";
                arg = seeded.firstAccountCode();
            }
            case "journal_entries" -> {
                sql = "DELETE FROM journal_entries WHERE idempotency_key = ?";
                arg = seeded.idempotencyKey();
            }
            default -> {
                sql = "DELETE FROM entry_lines WHERE journal_entry_id ="
                        + " (SELECT id FROM journal_entries WHERE idempotency_key = ?)";
                arg = seeded.idempotencyKey();
            }
        }

        assertDeniedWithInsufficientPrivilege(sql, arg);

        assertSeedIntact(seeded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accounts", "journal_entries", "entry_lines"})
    void truncateIsRefusedWithInsufficientPrivilege(String table) {
        Seeded seeded = seedCommittedEntry();

        assertDeniedWithInsufficientPrivilege("TRUNCATE " + table);

        assertSeedIntact(seeded);
    }

    @Test
    void theApplicationRoleCannotChangeTheSchemaOrDisableTriggers() {
        assertDeniedWithInsufficientPrivilege("CREATE TABLE ledger_app_must_not_create_this (id INT)");
        assertDeniedWithInsufficientPrivilege("ALTER TABLE journal_entries DISABLE TRIGGER ALL");
        assertDeniedWithInsufficientPrivilege("ALTER TABLE entry_lines ADD COLUMN tampered INT");
    }

    @Test
    void positiveControl_theApplicationRoleCanStillRegisterPostAndReadIncludingIdentityColumns() {
        // Registering an account and posting an entry each generate identity values on three tables:
        // if ledger_app needed a separate sequence grant, this would fail with 42501.
        Seeded seeded = seedCommittedEntry();

        assertSeedIntact(seeded);
        Integer lines = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entry_lines WHERE journal_entry_id ="
                        + " (SELECT id FROM journal_entries WHERE idempotency_key = ?)",
                Integer.class,
                seeded.idempotencyKey());
        assertThat(lines).isEqualTo(2);
    }

    // ------------------------------------------------------------------ default privileges for later tables

    // V3 made INSERT automatic for every later table, so a new table was writable by omission; V5 reverses that.
    // A new table is readable and nothing more until its own migration grants INSERT on named columns.
    @Test
    void aTableCreatedLaterByTheOwnerIsReadableButNotWritableForTheApplicationRole() {
        String table = "zz_default_privileges_probe";
        JdbcTemplate owner = ownerJdbc();
        owner.execute("CREATE TABLE " + table + " (id INT)");
        try {
            assertThat(hasPrivilege(owner, table, "SELECT")).as("SELECT").isTrue();
            assertThat(hasPrivilege(owner, table, "INSERT")).as("INSERT").isFalse();
            assertThat(hasPrivilege(owner, table, "UPDATE")).as("UPDATE").isFalse();
            assertThat(hasPrivilege(owner, table, "DELETE")).as("DELETE").isFalse();
            assertThat(hasPrivilege(owner, table, "TRUNCATE")).as("TRUNCATE").isFalse();
        } finally {
            owner.execute("DROP TABLE " + table);
        }
    }

    private static boolean hasPrivilege(JdbcTemplate owner, String table, String privilege) {
        Boolean result = owner.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)", Boolean.class, APP_USER, table, privilege);
        return Boolean.TRUE.equals(result);
    }
}
