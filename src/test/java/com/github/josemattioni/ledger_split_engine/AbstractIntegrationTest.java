package com.github.josemattioni.ledger_split_engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

public abstract class AbstractIntegrationTest {

    /** The role the application connects as; created by the init script, granted by Flyway V3. */
    protected static final String APP_USER = "ledger_app";

    /** Dev-only password, mirroring docker/initdb/01-create-ledger-app-role.sql. */
    protected static final String APP_PASSWORD = "ledger_app";

    private static final String INIT_SCRIPT = "01-create-ledger-app-role.sql";

    /**
     * Singleton container: started once per JVM and shared by every test class that extends
     * this one (Ryuk removes it at exit). A per-class {@code @Container} would be stopped after
     * the first class while Spring's cached context kept pointing at its dead port.
     *
     * <p>The same init script docker-compose mounts is copied into the Postgres entrypoint's
     * init directory, so the test database and the dev database create {@code ledger_app} the
     * same way. The path is relative to the JVM working directory (the project root under Maven).
     */
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/initdb/" + INIT_SCRIPT),
                    "/docker-entrypoint-initdb.d/" + INIT_SCRIPT);

    static {
        POSTGRES.start();
    }

    /**
     * The application connects as {@code ledger_app} (SELECT and INSERT only); Flyway migrates as
     * the container's own user, which owns the tables.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * A connection as the table owner (the container's superuser), for tests that must act as
     * the owner. Autocommit, so each statement is its own transaction.
     */
    protected static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** A header the owner inserted: its internal id and its public {@code entry_id}. */
    protected record SeededEntry(long id, UUID entryId) {}

    /**
     * Commits an entry as the table owner, in one transaction, with an explicit {@code created_at}: the only way
     * to put a backdated entry in the ledger now that {@code ledger_app} cannot supply a timestamp (or an id).
     * The lines are inserted in the order given, not necessarily in {@code line_number} order, and name their
     * accounts by code. {@code reversesEntryId} is the public id of the entry this one reverses, or
     * {@code null}; the link column is resolved here. The owner is for migrations and tests, never the application.
     */
    protected static SeededEntry insertEntryAsOwner(
            String idempotencyKey, Instant createdAt, UUID reversesEntryId, List<EntryLine> linesInInsertOrder)
            throws SQLException {
        return insertEntryAsOwner(null, idempotencyKey, createdAt, reversesEntryId, linesInInsertOrder);
    }

    /**
     * As {@link #insertEntryAsOwner(String, Instant, UUID, List)}, with an explicit internal {@code id} when
     * {@code explicitId} is not {@code null} ({@code OVERRIDING SYSTEM VALUE}, which only the owner may use).
     */
    protected static SeededEntry insertEntryAsOwner(
            Long explicitId,
            String idempotencyKey,
            Instant createdAt,
            UUID reversesEntryId,
            List<EntryLine> linesInInsertOrder)
            throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            Long reversesInternalId = null;
            if (reversesEntryId != null) {
                try (PreparedStatement lookup = connection.prepareStatement("SELECT id FROM journal_entries WHERE entry_id = ?")) {
                    lookup.setObject(1, reversesEntryId);
                    try (ResultSet rs = lookup.executeQuery()) {
                        assertThat(rs.next()).as("the entry to reverse exists").isTrue();
                        reversesInternalId = rs.getLong(1);
                    }
                }
            }
            long headerId;
            UUID entryId;
            String insertHeader = explicitId == null
                    ? "INSERT INTO journal_entries (idempotency_key, created_at, reverses_entry_id) VALUES (?, ?, ?)"
                            + " RETURNING id, entry_id"
                    : "INSERT INTO journal_entries (id, idempotency_key, created_at, reverses_entry_id)"
                            + " OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?) RETURNING id, entry_id";
            try (PreparedStatement header = connection.prepareStatement(insertHeader)) {
                int next = 1;
                if (explicitId != null) {
                    header.setLong(next++, explicitId);
                }
                header.setString(next++, idempotencyKey);
                header.setTimestamp(next++, Timestamp.from(createdAt));
                if (reversesInternalId == null) {
                    header.setNull(next, Types.BIGINT);
                } else {
                    header.setLong(next, reversesInternalId);
                }
                try (ResultSet rs = header.executeQuery()) {
                    rs.next();
                    headerId = rs.getLong(1);
                    entryId = rs.getObject(2, UUID.class);
                }
            }
            try (PreparedStatement line = connection.prepareStatement(
                    """
                    INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents)
                    VALUES (?, ?, (SELECT id FROM accounts WHERE code = ?), ?)
                    """)) {
                for (EntryLine entryLine : linesInInsertOrder) {
                    line.setLong(1, headerId);
                    line.setInt(2, entryLine.lineNumber());
                    line.setString(3, entryLine.accountCode());
                    line.setLong(4, entryLine.amountCents());
                    line.executeUpdate();
                }
            }
            connection.commit();
            return new SeededEntry(headerId, entryId);
        }
    }

    /** The port the tests register accounts through; injected into every Spring test that extends this class. */
    @Autowired
    protected AccountPort accountPort;

    /** A code that is valid for an account and unique across the whole test run. */
    protected static String uniqueCode(String prefix) {
        return prefix + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected static String uniqueIdempotencyKey() {
        return "key-" + UUID.randomUUID();
    }

    /** Registers a BRL account with a fresh unique code, as {@code ledger_app}, and returns the code. */
    protected String registerAccount(String prefix, AccountType type) {
        String code = uniqueCode(prefix);
        accountPort.register(new Account(code, null, type, Currency.BRL, null));
        return code;
    }

    /**
     * A fresh connection as {@code ledger_app}, the application's own identity, for tests that
     * need two sessions or must control the transaction themselves. The caller closes it.
     */
    protected static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    /**
     * A fresh connection as the table owner, for tests that must control the transaction
     * themselves (a header and its lines have to commit together). The caller closes it.
     */
    protected static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The table owner's user name, for tools run inside the container ({@code pg_dump}, {@code pg_restore}). */
    protected static String ownerUsername() {
        return POSTGRES.getUsername();
    }

    /**
     * The table owner's access to another database of the shared container (one a test created itself), for Flyway
     * and for tests that must act on it.
     */
    protected static DataSource ownerDataSourceOf(String database) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs a command inside the Postgres container (it reaches the server over the local socket as the owner). */
    protected static ExecResult runInPostgresContainer(String... command) throws IOException, InterruptedException {
        return POSTGRES.execInContainer(command);
    }

    /** Walks the cause chain to find the SQLSTATE Postgres actually reported, or null if there is none. */
    protected static String sqlStateOf(Throwable t) {
        for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
        }
        return null;
    }

    /** The name of the constraint a failed statement violated, as the server reported it, or null. */
    protected static String constraintOf(Throwable t) {
        for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql.getServerErrorMessage().getConstraint();
            }
        }
        return null;
    }

    /** The server's own message text for the first SQLException in the cause chain, or null. */
    protected static String serverMessageOf(Throwable t) {
        for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql.getServerErrorMessage().getMessage();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ deterministic two-connection concurrency

    /**
     * How long a concurrency test waits for a blocked statement to show up as blocked, and for it to finish.
     * Every {@code ledger_app} transaction is ended by the server after its {@code transaction_timeout} (30 s,
     * set on the role by the init script), and a transaction can be open for a poll plus the wait after the
     * other side lets go: twice this value. It must stay well under the bound so that a slow or broken run fails
     * with the helper's own message and not with a termination ({@code TransactionBoundGuardTest} checks it).
     */
    protected static final Duration CONCURRENCY_DEADLINE = Duration.ofSeconds(10);

    private static final Duration LOCK_POLL_INTERVAL = Duration.ofMillis(10);

    /** The server-side process id of {@code connection}'s backend, as {@code pg_stat_activity} lists it. */
    protected static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
                ResultSet rs = query.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Starts B's statement on another thread and returns only once the server reports B's backend waiting
     * on a lock, so the caller knows B is blocked on A and not merely slow to start. No sleeps: see
     * {@link #awaitLockWait}.
     */
    protected static <T> Future<T> startBlocked(ExecutorService executor, Connection b, Callable<T> statement)
            throws Exception {
        int bPid = backendPid(b);
        Future<T> blocked = executor.submit(statement);
        awaitLockWait(bPid, blocked);
        assertThat(blocked.isDone()).as("B must still be waiting for A").isFalse();
        return blocked;
    }

    /**
     * A bounded poll of {@code pg_stat_activity}; no fixed sleeps. It reads through one owner connection
     * (the owner can see every backend's wait state) and reuses one prepared statement, rather than
     * opening a new physical connection per poll. Fails with a clear message if B finishes without ever
     * waiting, or never shows {@code wait_event_type = 'Lock'} within {@link #CONCURRENCY_DEADLINE}.
     */
    protected static void awaitLockWait(int backendPid, Future<?> blocked) throws Exception {
        long deadline = System.nanoTime() + CONCURRENCY_DEADLINE.toNanos();
        String lastSeen = "<no row>";
        try (Connection owner = ownerConnection();
                PreparedStatement poll = owner.prepareStatement(
                        "SELECT COALESCE(wait_event_type, '<none>') FROM pg_stat_activity WHERE pid = ?")) {
            poll.setInt(1, backendPid);
            while (System.nanoTime() < deadline) {
                if (blocked.isDone()) {
                    fail("B's statement finished without ever waiting on A: " + describe(blocked));
                }
                try (ResultSet rs = poll.executeQuery()) {
                    if (rs.next()) {
                        lastSeen = rs.getString(1);
                        if ("Lock".equals(lastSeen)) {
                            return;
                        }
                    }
                }
                LockSupport.parkNanos(LOCK_POLL_INTERVAL.toNanos());
            }
        }
        fail("B's backend (pid " + backendPid + ") never showed wait_event_type = 'Lock' within "
                + CONCURRENCY_DEADLINE + "; last seen: " + lastSeen);
    }

    private static String describe(Future<?> finished) throws InterruptedException {
        try {
            return "returned " + finished.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return "threw " + e.getCause();
        } catch (TimeoutException e) {
            return "(result unavailable)";
        }
    }
}
