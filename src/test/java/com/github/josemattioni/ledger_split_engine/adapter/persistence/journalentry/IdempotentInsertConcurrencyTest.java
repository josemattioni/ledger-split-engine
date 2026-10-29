package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Why {@code INSERT … ON CONFLICT DO NOTHING} is enough for concurrent posts with one key, shown at
 * SQL level with two raw {@code ledger_app} connections and no sleeps. A holds an uncommitted header
 * for key K; B's insert for K waits on the unique index. The test proves B is really waiting (the
 * server reports {@code wait_event_type = 'Lock'} for B's backend) before it lets A finish, so the
 * outcome cannot depend on timing.
 *
 * <p>B always runs inside an explicit transaction and ends with a rollback. In the rollback case B's
 * insert creates a header with no lines; in autocommit that statement would commit at once and the
 * deferred {@code LDG03} trigger (at least two lines) would fail the commit, so B would get an
 * exception instead of the row.
 */
@SpringBootTest
class IdempotentInsertConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long registerAccountId(String prefix, AccountType type) {
        String code = registerAccount(prefix, type);
        return jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, code);
    }

    @Test
    void aWaitingInsertReturnsNoRowOnceTheFirstTransactionCommitsAndThenReadsItsEntry() throws Exception {
        long debitId = registerAccountId("seller_payable", AccountType.LIABILITY);
        long creditId = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // B is declared first so it is closed last: closing A first rolls A back and releases B.
        try (Connection b = appConnection();
                Connection a = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            HeaderRow headerOfA = insertPlainHeader(a, key);
            Future<List<Long>> bInsert = startBlocked(executor, b, () -> conflictInsert(b, key));

            insertLine(a, headerOfA.id(), 1, debitId, 9750L);
            insertLine(a, headerOfA.id(), 2, creditId, -9750L);
            a.commit();

            assertThat(bInsert.get(CONCURRENCY_DEADLINE.toSeconds(), TimeUnit.SECONDS))
                    .as("B's ON CONFLICT insert, after A committed")
                    .isEmpty();
            // A new statement in B's transaction (READ COMMITTED) sees what A committed.
            try (PreparedStatement read = b.prepareStatement(
                    """
                    SELECT je.entry_id, (SELECT COUNT(*) FROM entry_lines el WHERE el.journal_entry_id = je.id)
                    FROM journal_entries je
                    WHERE je.idempotency_key = ?
                    """)) {
                read.setString(1, key);
                try (ResultSet rs = read.executeQuery()) {
                    assertThat(rs.next()).as("B reads A's entry by key").isTrue();
                    assertThat(rs.getObject(1, UUID.class)).isEqualTo(headerOfA.entryId());
                    assertThat(rs.getInt(2)).isEqualTo(2);
                }
            }
            b.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(headerCount(key)).as("exactly A's entry exists").isEqualTo(1);
    }

    @Test
    void aWaitingInsertProceedsAndReturnsARowWhenTheFirstTransactionRollsBack() throws Exception {
        String key = uniqueIdempotencyKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // B is declared first so it is closed last: closing A first rolls A back and releases B.
        try (Connection b = appConnection();
                Connection a = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            insertPlainHeader(a, key);
            Future<List<Long>> bInsert = startBlocked(executor, b, () -> conflictInsert(b, key));

            a.rollback();

            assertThat(bInsert.get(CONCURRENCY_DEADLINE.toSeconds(), TimeUnit.SECONDS))
                    .as("B's ON CONFLICT insert, after A rolled back, returns its own new header")
                    .hasSize(1);
            // B's header has no lines, so B must not commit it (LDG03).
            b.rollback();
        } finally {
            executor.shutdownNow();
        }
        assertThat(headerCount(key)).as("neither transaction left a header").isZero();
    }

    private record HeaderRow(long id, UUID entryId) {}

    private HeaderRow insertPlainHeader(Connection connection, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id, entry_id")) {
            insert.setString(1, key);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return new HeaderRow(rs.getLong(1), rs.getObject(2, UUID.class));
            }
        }
    }

    private void insertLine(Connection connection, long headerId, int lineNumber, long accountId, long cents)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, headerId);
            insert.setInt(2, lineNumber);
            insert.setLong(3, accountId);
            insert.setLong(4, cents);
            insert.executeUpdate();
        }
    }

    private int headerCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }

    private static List<Long> conflictInsert(Connection connection, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO journal_entries (idempotency_key) VALUES (?)
                ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING
                RETURNING id
                """)) {
            insert.setString(1, key);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = insert.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
    }
}
