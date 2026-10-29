package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * The restore recipe the documentation gives, run for real: a data-only dump of a ledger that holds an entry and its
 * reversal, loaded with {@code pg_restore} into a freshly migrated database whose triggers are all on.
 *
 * <p>The load works in one transaction ({@code --single-transaction}), because an entry's header and its lines have to
 * arrive together (the two-line check refuses a header committed alone), and because the trigger functions resolve
 * their tables whatever search path the restore session has ({@code pg_restore} empties it). Each test works in
 * databases of its own, created in the shared container and dropped afterwards.
 */
class RestoreRecipeTest extends AbstractIntegrationTest {

    private final List<String> createdDatabases = new ArrayList<>();

    @AfterEach
    void dropDatabases() throws SQLException {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            for (String name : createdDatabases) {
                statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            }
        }
    }

    private String newMigratedDatabase(String prefix) throws SQLException {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        createdDatabases.add(name);
        Flyway.configure().dataSource(ownerDataSourceOf(name)).load().migrate();
        return name;
    }

    /** Two accounts, an entry between them and its exact reversal, committed by the owner in one transaction. */
    private void seedAnEntryAndItsReversal(String database) throws SQLException {
        try (Connection connection = ownerDataSourceOf(database).getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO accounts (code, type) VALUES ('seller_payable:1', 'LIABILITY'), ('acquirer_fee', 'EXPENSE')");
                statement.execute("INSERT INTO journal_entries (idempotency_key) VALUES ('restore-original')");
                statement.execute(linesOf("restore-original", 1000));
                statement.execute("""
                        INSERT INTO journal_entries (idempotency_key, reverses_entry_id)
                        SELECT 'restore-reversal', id FROM journal_entries WHERE idempotency_key = 'restore-original'
                        """);
                statement.execute(linesOf("restore-reversal", -1000));
            }
            connection.commit();
        }
    }

    private static String linesOf(String key, int cents) {
        return """
                INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents)
                SELECT je.id, l.n, (SELECT id FROM accounts WHERE code = l.code), l.sign * %d
                FROM journal_entries je, (VALUES (1, 'seller_payable:1', 1), (2, 'acquirer_fee', -1)) AS l(n, code, sign)
                WHERE je.idempotency_key = '%s'
                """.formatted(cents, key);
    }

    /** What a restored ledger must show: the same rows, with the same public ids and lines, as the one it came from. */
    private String contentsOf(String database) throws SQLException {
        try (Connection connection = ownerDataSourceOf(database).getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("""
                        SELECT (SELECT count(*) FROM accounts) || ' accounts, '
                            || (SELECT count(*) FROM journal_entries) || ' entries, '
                            || (SELECT count(*) FROM entry_lines) || ' lines; ids '
                            || COALESCE((SELECT string_agg(entry_id::text, ',' ORDER BY id) FROM journal_entries), '')
                            || '; lines ' || COALESCE((SELECT string_agg(
                                   journal_entry_id || ':' || line_number || ':' || account_id || ':' || amount_cents, ',' ORDER BY id)
                               FROM entry_lines), '')
                        """)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String dumpDataOf(String database) throws IOException, InterruptedException {
        String file = "/tmp/" + database + ".dump";
        // flyway_schema_history is the migration tool's own table: the target already has its own rows in it.
        ExecResult dump = runInPostgresContainer(
                "pg_dump", "-U", ownerUsername(), "-d", database, "--data-only", "-Fc",
                "--exclude-table=flyway_schema_history", "-f", file);
        assertThat(dump.getExitCode()).as("pg_dump stderr: %s", dump.getStderr()).isZero();
        return file;
    }

    private ExecResult restoreDataInto(String database, String file, boolean inOneTransaction)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "pg_restore", "-U", ownerUsername(), "-d", database, "--data-only", "--exit-on-error"));
        if (inOneTransaction) {
            command.add("--single-transaction");
        }
        command.add(file);
        return runInPostgresContainer(command.toArray(String[]::new));
    }

    @Test
    void aDataOnlyRestoreInOneTransactionLoadsTheLedgerWithTheTriggersOn() throws Exception {
        String source = newMigratedDatabase("restore_source");
        String target = newMigratedDatabase("restore_target");
        seedAnEntryAndItsReversal(source);
        String file = dumpDataOf(source);

        ExecResult restore = restoreDataInto(target, file, true);

        assertThat(restore.getExitCode()).as("pg_restore stderr: %s", restore.getStderr()).isZero();
        assertThat(contentsOf(target)).startsWith("2 accounts, 2 entries, 4 lines;").isEqualTo(contentsOf(source));
    }

    @Test
    void aRestoreThatCommitsEachTableOnItsOwnIsRefusedBecauseTheHeaderComesBeforeItsLines() throws Exception {
        String source = newMigratedDatabase("restore_source");
        String target = newMigratedDatabase("restore_target");
        seedAnEntryAndItsReversal(source);
        String file = dumpDataOf(source);

        ExecResult restore = restoreDataInto(target, file, false);

        // pg_restore prints the server's message, not its SQLSTATE: this is the two-line check (LDG03).
        assertThat(restore.getExitCode()).as("pg_restore stdout: %s", restore.getStdout()).isNotZero();
        assertThat(restore.getStderr()).contains("at least 2 are required");
        assertThat(contentsOf(target)).contains(" 0 entries, 0 lines;");
    }
}
