package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.UnknownAccountException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code insertOrFind} against a real database, as {@code ledger_app}, with real commits (the
 * adapter's own transaction). The adapter reports state: these tests never expect it to decide
 * whether an existing key is a retry or a reuse.
 */
@SpringBootTest
class IdempotentInsertOrFindTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcJournalEntryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static JournalEntry request(String key, String debitAccount, String creditAccount, long cents) {
        return new JournalEntry(
                null, key, List.of(new EntryLine(1, debitAccount, cents), new EntryLine(2, creditAccount, -cents)), null);
    }

    private int headerCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }

    private int lineCount(String key) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM entry_lines el
                JOIN journal_entries je ON je.id = el.journal_entry_id
                WHERE je.idempotency_key = ?
                """,
                Integer.class,
                key);
    }

    @Test
    void newKeyReturnsCreatedAndWritesTheRows() {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();

        PostOutcome outcome = adapter.insertOrFind(request(key, debit, credit, 9750L));

        assertThat(outcome).isInstanceOfSatisfying(PostOutcome.Created.class, created -> {
            JournalEntry entry = created.entry();
            assertThat(entry.entryId()).isNotNull();
            assertThat(entry.createdAt()).isNotNull();
            assertThat(entry.idempotencyKey()).isEqualTo(key);
            assertThat(entry.lines())
                    .containsExactly(new EntryLine(1, debit, 9750L), new EntryLine(2, credit, -9750L));
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT entry_id FROM journal_entries WHERE idempotency_key = ?", UUID.class, key))
                    .isEqualTo(entry.entryId());
        });
        assertThat(headerCount(key)).isEqualTo(1);
        assertThat(lineCount(key)).isEqualTo(2);
        assertThat(adapter.balanceOf(debit)).isEqualTo(9750L);
    }

    @Test
    void existingKeyReturnsAlreadyExistsCarryingTheOriginalEntry() {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();
        JournalEntry original = ((PostOutcome.Created) adapter.insertOrFind(request(key, debit, credit, 500L))).entry();

        PostOutcome second = adapter.insertOrFind(request(key, debit, credit, 500L));

        assertThat(second).isInstanceOfSatisfying(PostOutcome.AlreadyExists.class, existing -> {
            assertThat(existing.stored().entryId()).isEqualTo(original.entryId());
            assertThat(existing.stored().createdAt()).isEqualTo(original.createdAt());
            assertThat(existing.stored().idempotencyKey()).isEqualTo(key);
            assertThat(existing.stored().lines()).isEqualTo(original.lines());
        });
        assertThat(headerCount(key)).isEqualTo(1);
        assertThat(lineCount(key)).isEqualTo(2);
        assertThat(adapter.balanceOf(debit)).isEqualTo(500L);
    }

    @Test
    void existingKeyReturnsTheStoredLinesInLineNumberOrderWhateverTheInsertOrder() throws SQLException {
        String first = registerAccount("seller_payable", AccountType.LIABILITY);
        String second = registerAccount("marketplace_commission", AccountType.REVENUE);
        String third = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();
        List<EntryLine> inLineNumberOrder = List.of(
                new EntryLine(1, first, 9750L), new EntryLine(2, second, -750L), new EntryLine(3, third, -9000L));
        // Inserted 3, 2, 1, so heap order is the reverse of line_number order.
        insertEntryAsOwner(
                key, Instant.now(), null, List.of(inLineNumberOrder.get(2), inLineNumberOrder.get(1), inLineNumberOrder.get(0)));

        PostOutcome outcome = adapter.insertOrFind(request(key, first, second, 1L));

        assertThat(outcome).isInstanceOfSatisfying(PostOutcome.AlreadyExists.class, existing ->
                assertThat(existing.stored().lines()).containsExactlyElementsOf(inLineNumberOrder));
    }

    @Test
    void existingKeyWithDifferentLinesStillReturnsAlreadyExistsWithTheStoredLines() {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();
        adapter.insertOrFind(request(key, debit, credit, 500L));

        // The adapter does not compare payloads: a different amount is still just "the key exists".
        PostOutcome outcome = adapter.insertOrFind(request(key, debit, credit, 1L));

        assertThat(outcome).isInstanceOfSatisfying(PostOutcome.AlreadyExists.class, existing ->
                assertThat(existing.stored().lines())
                        .containsExactly(new EntryLine(1, debit, 500L), new EntryLine(2, credit, -500L)));
        assertThat(headerCount(key)).isEqualTo(1);
        assertThat(lineCount(key)).isEqualTo(2);
        assertThat(adapter.balanceOf(debit)).isEqualTo(500L);
    }

    @Test
    void newKeyWithAnUnknownAccountThrowsLeavesNoHeaderAndKeepsTheKeyUsable() {
        String known = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String unknown = uniqueCode("does_not_exist");
        String key = uniqueIdempotencyKey();

        assertThatThrownBy(() -> adapter.insertOrFind(request(key, known, unknown, 100L)))
                .isInstanceOf(UnknownAccountException.class)
                .hasMessageContaining(unknown);

        assertThat(headerCount(key)).isZero();
        assertThat(lineCount(key)).isZero();

        // The failed request did not consume the key: the same key with valid accounts is a new post.
        String credit = registerAccount("seller_payable", AccountType.LIABILITY);
        assertThat(adapter.insertOrFind(request(key, known, credit, 100L))).isInstanceOf(PostOutcome.Created.class);
        assertThat(headerCount(key)).isEqualTo(1);
    }

    // Precedence test (two defects on purpose: a key that exists AND a request naming an unknown
    // account). The key is looked up before any account is resolved, so the stored entry wins.
    @Test
    void precedence_anExistingKeyIsFoundBeforeTheRequestsAccountsAreResolved() {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();
        adapter.insertOrFind(request(key, debit, credit, 500L));

        PostOutcome outcome = adapter.insertOrFind(request(key, debit, uniqueCode("does_not_exist"), 500L));

        assertThat(outcome).isInstanceOf(PostOutcome.AlreadyExists.class);
    }

    @Test
    void anOldEntryStillReturnsAlreadyExistsBecauseKeysDoNotExpire() throws SQLException {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();
        Instant fiveYearsAgo = Instant.now().minus(Duration.ofDays(5 * 365));
        insertEntryAsOwner(
                key, fiveYearsAgo, null, List.of(new EntryLine(1, debit, 700L), new EntryLine(2, credit, -700L)));

        PostOutcome outcome = adapter.insertOrFind(request(key, debit, credit, 700L));

        assertThat(outcome).isInstanceOfSatisfying(PostOutcome.AlreadyExists.class, existing -> {
            assertThat(existing.stored().createdAt()).isBefore(Instant.now().minus(Duration.ofDays(4 * 365)));
            assertThat(existing.stored().lines())
                    .containsExactly(new EntryLine(1, debit, 700L), new EntryLine(2, credit, -700L));
        });
        assertThat(headerCount(key)).isEqualTo(1);
    }

    // The concurrency tests cannot cover this: READ COMMITTED is also the database default, so they
    // pass with or without the annotation. Replay correctness depends on it (design.md, Decision 2).
    @Test
    void insertOrFindIsPinnedToReadCommitted() throws NoSuchMethodException {
        Method method = JdbcJournalEntryAdapter.class.getMethod("insertOrFind", JournalEntry.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).as("@Transactional on insertOrFind").isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }
}
