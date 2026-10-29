package com.github.josemattioni.ledger_split_engine.adapter.persistence.account;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Accounts are part of the append-only ledger (V5): the triggers that V3 put on {@code journal_entries} and
 * {@code entry_lines} refuse to change or remove an account even for the table owner, which gets past the
 * privileges. Every statement runs as the owner through {@link #ownerJdbc()}, each in autocommit.
 *
 * <p>Row-level triggers only fire for rows that exist, so each test targets accounts that are really there:
 * one with an entry's lines and one with none, because a foreign-key-referenced row and a free one are
 * different cases for a delete.
 *
 * <p>A bare {@code TRUNCATE accounts} never reaches its trigger: Postgres refuses it first, because
 * {@code entry_lines} references {@code accounts} ({@code 0A000}), exactly as slice 3 found for
 * {@code journal_entries}. The trigger is reached by {@code TRUNCATE ... CASCADE}, or by listing
 * {@code entry_lines} as well, with {@code accounts} first (the triggers fire in the order the tables are
 * listed, so the message names the table whose trigger fired).
 */
@SpringBootTest
class AccountsImmutableTest extends AbstractIntegrationTest {

    private static final String LDG02 = "LDG02";

    @Autowired
    private JournalEntryPort journalEntryPort;

    /** An account that has the lines of a committed entry, and one that has none. */
    private record Seeded(String withLines, String counterparty, String withoutLines) {}

    private Seeded seed() {
        String withLines = uniqueCode("seller_payable");
        String counterparty = uniqueCode("acquirer_fee");
        String withoutLines = uniqueCode("settlement_hold");
        accountPort.register(new Account(withLines, null, AccountType.LIABILITY, Currency.BRL, null));
        accountPort.register(new Account(counterparty, null, AccountType.EXPENSE, Currency.BRL, null));
        accountPort.register(new Account(withoutLines, null, AccountType.ASSET, Currency.BRL, null));
        journalEntryPort.insertOrFind(new JournalEntry(
                null,
                "key-" + UUID.randomUUID(),
                List.of(new EntryLine(1, withLines, 100L), new EntryLine(2, counterparty, -100L)),
                null));
        return new Seeded(withLines, counterparty, withoutLines);
    }

    private void assertRefusedAsAppendOnly(String sql, String expectedMessage, Object... args) {
        Throwable thrown = catchThrowable(() -> ownerJdbc().update(sql, args));

        assertThat(thrown).as("statement must be refused: %s", sql).isNotNull();
        assertThat(sqlStateOf(thrown)).as("SQLSTATE for: %s", sql).isEqualTo(LDG02);
        assertThat(serverMessageOf(thrown)).contains(expectedMessage).contains("append-only");
    }

    /** The three accounts are as registered (no owner_ref, the type they were given, BRL) and the entry's lines remain. */
    private void assertSeedIntact(Seeded seeded) {
        JdbcTemplate owner = ownerJdbc();
        Map<String, AccountType> expected = Map.of(
                seeded.withLines(), AccountType.LIABILITY,
                seeded.counterparty(), AccountType.EXPENSE,
                seeded.withoutLines(), AccountType.ASSET);
        expected.forEach((code, type) -> {
            Map<String, Object> row = owner.queryForMap(
                    "SELECT owner_ref, type, currency FROM accounts WHERE code = ?", code);
            assertThat(row.get("owner_ref")).as("owner_ref of %s", code).isNull();
            assertThat(row.get("type")).as("type of %s", code).isEqualTo(type.name());
            assertThat(row.get("currency")).as("currency of %s", code).isEqualTo("BRL");
        });
        assertThat(journalEntryPort.balanceOf(seeded.withLines())).isEqualTo(100L);
        assertThat(journalEntryPort.balanceOf(seeded.counterparty())).isEqualTo(-100L);
    }

    // ------------------------------------------------------------------ UPDATE and DELETE

    @Test
    void ownerUpdateOfAnAccountThatHasLinesIsRefused() {
        Seeded seeded = seed();

        assertRefusedAsAppendOnly(
                "UPDATE accounts SET owner_ref = 'tampered' WHERE code = ?",
                "UPDATE on accounts is not allowed",
                seeded.withLines());

        assertSeedIntact(seeded);
    }

    @Test
    void ownerUpdateOfAnAccountThatHasNoLinesIsRefused() {
        Seeded seeded = seed();

        assertRefusedAsAppendOnly(
                "UPDATE accounts SET type = 'REVENUE' WHERE code = ?",
                "UPDATE on accounts is not allowed",
                seeded.withoutLines());

        assertSeedIntact(seeded);
    }

    @Test
    void ownerDeleteOfAnAccountThatHasLinesIsRefused() {
        Seeded seeded = seed();

        assertRefusedAsAppendOnly(
                "DELETE FROM accounts WHERE code = ?", "DELETE on accounts is not allowed", seeded.withLines());

        assertSeedIntact(seeded);
    }

    @Test
    void ownerDeleteOfAnAccountThatHasNoLinesIsRefused() {
        Seeded seeded = seed();

        assertRefusedAsAppendOnly(
                "DELETE FROM accounts WHERE code = ?", "DELETE on accounts is not allowed", seeded.withoutLines());

        assertSeedIntact(seeded);
    }

    @Test
    void anUpdateThatMatchesNoRowSucceedsBecauseThereIsNothingToProtect() {
        Seeded seeded = seed();

        int updated = ownerJdbc()
                .update("UPDATE accounts SET owner_ref = 'x' WHERE code = ?", uniqueCode("does_not_exist"));

        assertThat(updated).isZero();
        assertSeedIntact(seeded);
    }

    // ------------------------------------------------------------------ TRUNCATE

    @Test
    void aBareTruncateOfAccountsIsStoppedByTheForeignKeyBeforeTheTriggerIsReached() {
        Seeded seeded = seed();

        Throwable thrown = catchThrowable(() -> ownerJdbc().execute("TRUNCATE accounts"));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo("0A000");
        assertSeedIntact(seeded);
    }

    @Test
    void ownerTruncateOfAccountsWithCascadeIsRefusedByTheAccountsTrigger() {
        Seeded seeded = seed();

        Throwable thrown = catchThrowable(() -> ownerJdbc().execute("TRUNCATE accounts CASCADE"));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(LDG02);
        assertThat(serverMessageOf(thrown)).contains("TRUNCATE on accounts is not allowed").contains("append-only");
        assertSeedIntact(seeded);
    }

    @Test
    void ownerTruncateOfAccountsListedBeforeEntryLinesIsRefusedByTheAccountsTrigger() {
        Seeded seeded = seed();

        // Both tables listed so the foreign-key check passes; accounts first, so its trigger is the one that fires.
        Throwable thrown = catchThrowable(() -> ownerJdbc().execute("TRUNCATE accounts, entry_lines"));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(LDG02);
        assertThat(serverMessageOf(thrown)).contains("TRUNCATE on accounts is not allowed").contains("append-only");
        assertSeedIntact(seeded);
    }

    // ------------------------------------------------------------------ the positive control

    @Test
    void positiveControl_theTriggersBlockOnlyMutationNotTheOwnersInserts() {
        Seeded seeded = seed();
        String code = uniqueCode("owner_registered");

        int inserted = ownerJdbc().update("INSERT INTO accounts (code, type) VALUES (?, 'ASSET')", code);

        assertThat(inserted).isEqualTo(1);
        assertThat(accountPort.findByCode(code)).isPresent();
        assertSeedIntact(seeded);
    }
}
