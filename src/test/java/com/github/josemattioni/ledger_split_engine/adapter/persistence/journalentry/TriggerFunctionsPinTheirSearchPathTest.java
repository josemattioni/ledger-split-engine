package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The database checks read the ledger tables themselves, whatever the session's search path is. Every trigger
 * function once named {@code entry_lines} or {@code journal_entries} without a schema and set no search path, and
 * Postgres searches the temporary schema first: a {@code ledger_app} session (it holds {@code TEMPORARY}) could create
 * a temporary table with a ledger table's name, and the check read that one instead, so it passed.
 *
 * <p>Each attack below has exactly one defect, the thing the check must refuse, and one shadow that would hide it.
 * They are raw rows as {@code ledger_app} with real commits (no {@code @Transactional} rollback): a refusal at commit
 * is what a deferred check is, and what matters afterwards is what a later transaction can see. The shadow tables live
 * and die with the connection each attack opens.
 */
@SpringBootTest
class TriggerFunctionsPinTheirSearchPathTest extends AbstractIntegrationTest {

    private static final String PINNED_SEARCH_PATH = "search_path=public, pg_temp";
    private static final String SHADOW_ENTRY_LINES =
            "CREATE TEMP TABLE entry_lines (journal_entry_id bigint, line_number int, account_id bigint, amount_cents bigint)";

    private String codeA;
    private String codeB;
    private long a;
    private long b;
    private long c;
    private long d;

    @BeforeEach
    void setUp() {
        codeA = registerAccount("seller_payable", AccountType.LIABILITY);
        codeB = registerAccount("acquirer_fee", AccountType.EXPENSE);
        a = accountId(codeA);
        b = accountId(codeB);
        c = accountId(registerAccount("marketplace_commission", AccountType.REVENUE));
        d = accountId(registerAccount("settlement_reserve", AccountType.ASSET));
    }

    // ------------------------------------------------------------------ helpers

    private static long accountId(String code) {
        return ownerJdbc().queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, code);
    }

    /** An entry committed by an earlier transaction: header and two lines (+1000 on A, -1000 on B). */
    private SeededEntry committedEntry(String key) throws SQLException {
        return insertEntryAsOwner(
                key,
                Instant.now().minus(1, ChronoUnit.HOURS),
                null,
                List.of(new EntryLine(1, codeA, 1000L), new EntryLine(2, codeB, -1000L)));
    }

    private int lineCountOf(long entryId) {
        return ownerJdbc().queryForObject("SELECT COUNT(*) FROM entry_lines WHERE journal_entry_id = ?", Integer.class, entryId);
    }

    private int headerCountOf(String key) {
        return ownerJdbc().queryForObject("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }

    private long balanceOf(long accountId) {
        return ownerJdbc().queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM entry_lines WHERE account_id = ?", Long.class, accountId);
    }

    @FunctionalInterface
    private interface InTransaction {
        void run(Connection connection) throws SQLException;
    }

    /** Runs the block as {@code ledger_app} in one transaction and commits it (a deferred check refuses at the commit). */
    private static void asLedgerApp(InTransaction block) throws SQLException {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            try {
                block.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long insertHeader(Connection connection, String key, Long reversesInternalId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO journal_entries (idempotency_key, reverses_entry_id) VALUES (?, ?) RETURNING id")) {
            insert.setString(1, key);
            if (reversesInternalId == null) {
                insert.setNull(2, Types.BIGINT);
            } else {
                insert.setLong(2, reversesInternalId);
            }
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertLine(Connection connection, long entryId, int lineNumber, long accountId, long cents)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, entryId);
            insert.setInt(2, lineNumber);
            insert.setLong(3, accountId);
            insert.setLong(4, cents);
            insert.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ the five attacks

    // LDG06: the defect is a pair of lines (+5000/-5000) added to an entry from an earlier transaction.
    @Test
    void shadowingTheJournalEntriesTableDoesNotLetALineIntoAnEarlierEntry() throws Exception {
        SeededEntry earlier = committedEntry(uniqueIdempotencyKey());

        Throwable thrown = catchThrowable(() -> asLedgerApp(connection -> {
            execute(connection, "CREATE TEMP TABLE journal_entries (id bigint)");
            execute(connection, "INSERT INTO journal_entries (id) VALUES (" + earlier.id() + ")");
            insertLine(connection, earlier.id(), 3, c, 5000L);
            insertLine(connection, earlier.id(), 4, d, -5000L);
        }));

        assertThat(thrown).as("the lines are refused although journal_entries is shadowed").isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo("LDG06");
        assertThat(lineCountOf(earlier.id())).as("the entry's lines are as they were").isEqualTo(2);
        assertThat(balanceOf(c)).isZero();
        assertThat(balanceOf(d)).isZero();
    }

    // LDG01: the defect is an entry that sums to 100 (+700/-600); the shadow shows two lines that cancel out.
    @Test
    void shadowingTheEntryLinesTableDoesNotLetAnUnbalancedEntryCommit() {
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> asLedgerApp(connection -> {
            long entry = insertHeader(connection, key, null);
            insertLine(connection, entry, 1, a, 700L);
            insertLine(connection, entry, 2, b, -600L);
            execute(connection, SHADOW_ENTRY_LINES);
            execute(connection, "INSERT INTO entry_lines VALUES (" + entry + ", 1, " + a + ", 1), (" + entry + ", 2, " + b + ", -1)");
        }));

        assertThat(thrown).as("the commit is refused although entry_lines is shadowed").isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo("LDG01");
        assertThat(headerCountOf(key)).as("nothing was written").isZero();
    }

    // LDG03: the defect is an entry with no lines at all; the shadow shows two for it.
    @Test
    void shadowingTheEntryLinesTableDoesNotLetAnEntryWithoutLinesCommit() {
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> asLedgerApp(connection -> {
            long entry = insertHeader(connection, key, null);
            execute(connection, SHADOW_ENTRY_LINES);
            execute(connection, "INSERT INTO entry_lines VALUES (" + entry + ", 1, " + a + ", 1), (" + entry + ", 2, " + b + ", -1)");
        }));

        assertThat(thrown).as("the commit is refused although entry_lines is shadowed").isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo("LDG03");
        assertThat(headerCountOf(key)).as("nothing was written").isZero();
    }

    // LDG04: the defect is a reversal that balances (-900/+900) but is not the original's negation (-1000/+1000).
    @Test
    void shadowingTheEntryLinesTableDoesNotLetAReversalThatIsNotTheNegationCommit() throws Exception {
        SeededEntry original = committedEntry(uniqueIdempotencyKey());
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> asLedgerApp(connection -> {
            long reversal = insertHeader(connection, key, original.id());
            insertLine(connection, reversal, 1, a, -900L);
            insertLine(connection, reversal, 2, b, 900L);
            execute(connection, SHADOW_ENTRY_LINES);
            // The shadow shows the original and an exact negation of it.
            execute(connection, "INSERT INTO entry_lines VALUES"
                    + " (" + original.id() + ", 1, " + a + ", 1000), (" + original.id() + ", 2, " + b + ", -1000),"
                    + " (" + reversal + ", 1, " + a + ", -1000), (" + reversal + ", 2, " + b + ", 1000)");
        }));

        assertThat(thrown).as("the commit is refused although entry_lines is shadowed").isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo("LDG04");
        assertThat(headerCountOf(key)).as("nothing was written").isZero();
    }

    // LDG05: the defect is the original, which is itself a reversal; the reversal's lines are the exact negation of it.
    @Test
    void shadowingTheJournalEntriesTableDoesNotLetAReversalOfAReversalCommit() throws Exception {
        SeededEntry original = committedEntry(uniqueIdempotencyKey());
        SeededEntry reversal = insertEntryAsOwner(
                uniqueIdempotencyKey(),
                Instant.now().minus(30, ChronoUnit.MINUTES),
                original.entryId(),
                List.of(new EntryLine(1, codeA, -1000L), new EntryLine(2, codeB, 1000L)));
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> asLedgerApp(connection -> {
            long second = insertHeader(connection, key, reversal.id());
            insertLine(connection, second, 1, a, 1000L);
            insertLine(connection, second, 2, b, -1000L);
            execute(connection, "CREATE TEMP TABLE journal_entries (id bigint, reverses_entry_id bigint)");
            // The shadow says the reversal is an ordinary entry.
            execute(connection, "INSERT INTO journal_entries VALUES (" + reversal.id() + ", NULL)");
        }));

        assertThat(thrown).as("the commit is refused although journal_entries is shadowed").isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo("LDG05");
        assertThat(headerCountOf(key)).as("nothing was written").isZero();
    }

    // ------------------------------------------------------------------ the rule that catches the next function

    @Test
    void everyTriggerFunctionOfTheLedgerFixesItsSearchPath() {
        String triggerFunctions = """
                SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.prorettype = 'trigger'::regtype
                """;

        List<String> all = ownerJdbc().queryForList(triggerFunctions + " ORDER BY p.proname", String.class);
        List<String> notPinned = ownerJdbc().queryForList(
                triggerFunctions + " AND p.proconfig IS DISTINCT FROM ARRAY['" + PINNED_SEARCH_PATH + "']::text[] ORDER BY p.proname",
                String.class);

        assertThat(all)
                .as("the known trigger functions are found, so an empty result cannot pass")
                .contains(
                        "check_entry_balance",
                        "forbid_ledger_mutation",
                        "check_entry_has_enough_lines",
                        "check_reversal_matches_original",
                        "check_line_added_with_its_entry");
        assertThat(notPinned).as("trigger functions that do not fix their search path to " + PINNED_SEARCH_PATH).isEmpty();
    }
}
