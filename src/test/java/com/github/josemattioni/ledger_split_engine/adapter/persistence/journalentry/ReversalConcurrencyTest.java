package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Why the constraint, with no lock and no pre-check, is enough for concurrent reversals of one entry,
 * shown at SQL level with two raw {@code ledger_app} connections and no sleeps (the same technique as
 * {@link IdempotentInsertConcurrencyTest}). A holds an uncommitted reversal of T; B runs the adapter's
 * own header insert for a reversal of T. The test proves B is really waiting (the server reports
 * {@code wait_event_type = 'Lock'}) before it lets A finish, so the outcome cannot depend on timing.
 *
 * <p>B always runs in an explicit transaction and ends with a rollback: when B gets a row its header
 * has no lines, and committing that would fail the deferred {@code LDG03} check.
 */
@SpringBootTest
class ReversalConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcJournalEntryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String debitCode;
    private String creditCode;
    private long originalId;

    @BeforeEach
    void setUp() {
        debitCode = registerAccount("seller_payable", AccountType.LIABILITY);
        creditCode = registerAccount("acquirer_fee", AccountType.EXPENSE);
        JournalEntry original = ((PostOutcome.Created) adapter.insertOrFind(new JournalEntry(
                        null,
                        uniqueIdempotencyKey(),
                        List.of(new EntryLine(1, debitCode, 500L), new EntryLine(2, creditCode, -500L)),
                        null)))
                .entry();
        originalId = jdbcTemplate.queryForObject(
                "SELECT id FROM journal_entries WHERE entry_id = ?", Long.class, original.entryId());
    }

    @Test
    void aWaitingReversalUnderAnotherKeyFailsOnTheAtMostOnceConstraintOnceTheFirstCommits() throws Exception {
        String keyOfA = uniqueIdempotencyKey();
        String keyOfB = uniqueIdempotencyKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // B is declared first so it is closed last: closing A first rolls A back and releases B.
        try (Connection b = appConnection();
                Connection a = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            long reversalOfA = insertReversalHeader(a, keyOfA);
            Future<List<Long>> bInsert = startBlocked(executor, b, () -> reversalInsert(b, keyOfB));

            insertNegatedLines(a, reversalOfA);
            a.commit();

            Throwable thrown = catchThrowable(() -> bInsert.get(CONCURRENCY_DEADLINE.toSeconds(), TimeUnit.SECONDS));
            assertThat(thrown).as("B's insert, after A committed").isNotNull();
            assertThat(sqlStateOf(thrown)).isEqualTo("23505");
            assertThat(constraintOf(thrown)).isEqualTo("journal_entries_reverses_entry_uq");
            b.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(reversalsOfTheOriginal()).as("exactly A's reversal exists").isEqualTo(1);
        assertThat(headerCount(keyOfB)).isZero();
    }

    @Test
    void aWaitingReversalProceedsAndReturnsARowWhenTheFirstTransactionRollsBack() throws Exception {
        String keyOfA = uniqueIdempotencyKey();
        String keyOfB = uniqueIdempotencyKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection b = appConnection();
                Connection a = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            insertReversalHeader(a, keyOfA);
            Future<List<Long>> bInsert = startBlocked(executor, b, () -> reversalInsert(b, keyOfB));

            a.rollback();

            assertThat(bInsert.get(CONCURRENCY_DEADLINE.toSeconds(), TimeUnit.SECONDS))
                    .as("B's insert, after A rolled back, returns its own new header")
                    .hasSize(1);
            // B's header has no lines, so B must not commit it (LDG03).
            b.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(reversalsOfTheOriginal()).as("neither transaction left a reversal").isZero();
    }

    // A concurrent retry: the same key for the same target. B waits on the key's own unique index, and once
    // A commits it gets no row and no error: the replay path, never "already reversed".
    @Test
    void aWaitingRetryUnderTheSameKeyReturnsNoRowOnceTheFirstCommits() throws Exception {
        String sharedKey = uniqueIdempotencyKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection b = appConnection();
                Connection a = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            long reversalOfA = insertReversalHeader(a, sharedKey);
            Future<List<Long>> bInsert = startBlocked(executor, b, () -> reversalInsert(b, sharedKey));

            insertNegatedLines(a, reversalOfA);
            a.commit();

            assertThat(bInsert.get(CONCURRENCY_DEADLINE.toSeconds(), TimeUnit.SECONDS))
                    .as("B's ON CONFLICT insert under A's key, after A committed")
                    .isEmpty();
            b.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(reversalsOfTheOriginal()).isEqualTo(1);
        assertThat(headerCount(sharedKey)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ SQL (the adapter's own statement for B)

    private long insertReversalHeader(Connection connection, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO journal_entries (idempotency_key, reverses_entry_id) VALUES (?, ?) RETURNING id")) {
            insert.setString(1, key);
            insert.setLong(2, originalId);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertNegatedLines(Connection connection, long reversalId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents)
                VALUES (?, 1, (SELECT id FROM accounts WHERE code = ?), -500),
                       (?, 2, (SELECT id FROM accounts WHERE code = ?), 500)
                """)) {
            insert.setLong(1, reversalId);
            insert.setString(2, debitCode);
            insert.setLong(3, reversalId);
            insert.setString(4, creditCode);
            insert.executeUpdate();
        }
    }

    /** The adapter's header insert for a reversal, verbatim, so B blocks and fails exactly as the adapter would. */
    private List<Long> reversalInsert(Connection connection, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO journal_entries (idempotency_key, reverses_entry_id)
                VALUES (?, ?)
                ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING
                RETURNING id
                """)) {
            insert.setString(1, key);
            insert.setLong(2, originalId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = insert.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
    }

    private int reversalsOfTheOriginal() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE reverses_entry_id = ?", Integer.class, originalId);
    }

    private int headerCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }
}
