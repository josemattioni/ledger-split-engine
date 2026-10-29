package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryPort;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.MixedCurrencyException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.UnknownAccountException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcJournalEntryAdapter implements JournalEntryPort {

    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "journal_entries_idempotency_key_uq";
    private static final String REVERSES_ENTRY_CONSTRAINT = "journal_entries_reverses_entry_uq";

    /**
     * One query for every way an entry is read back, so the lookup by key and the lookup by
     * {@code entry_id} cannot drift. {@code reverses_entry_id} is returned as the original's <em>public</em>
     * id (the self-join), never the internal one, because that is what {@code sameRequestAs} compares.
     */
    private static final String LOAD_ENTRY = """
            SELECT je.entry_id, je.idempotency_key, je.created_at, orig.entry_id AS reverses_entry_id,
                   el.line_number, a.code, el.amount_cents
            FROM journal_entries je
            LEFT JOIN journal_entries orig ON orig.id = je.reverses_entry_id
            JOIN entry_lines el ON el.journal_entry_id = je.id
            JOIN accounts a ON a.id = el.account_id
            """;

    private static final String LOAD_BY_KEY = LOAD_ENTRY + "WHERE je.idempotency_key = ? ORDER BY el.line_number";
    private static final String LOAD_BY_ENTRY_ID = LOAD_ENTRY + "WHERE je.entry_id = ? ORDER BY el.line_number";

    private final JdbcTemplate jdbcTemplate;

    public JdbcJournalEntryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * READ COMMITTED is pinned here, not inherited from the database default: when the header insert
     * finds the key taken, the load that follows must be a new statement with a new snapshot so it
     * sees the entry the other transaction committed. Under REPEATABLE READ a conflict with a row
     * committed after the snapshot raises 40001 instead.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PostOutcome insertOrFind(JournalEntry entry) {
        Optional<InsertedHeader> inserted = insertHeaderIfAbsent(entry);
        if (inserted.isEmpty()) {
            return new PostOutcome.AlreadyExists(loadByKey(entry.idempotencyKey()));
        }

        // Only a new key reaches account resolution. A failure here rolls the header back with it,
        // so a rejected request does not consume the key.
        InsertedHeader header = inserted.get();
        Map<String, ResolvedAccount> byCode = resolveAccountsOrThrow(entry);
        insertLines(header.id(), entry.lines(), byCode);

        return new PostOutcome.Created(new JournalEntry(
                header.entryId(), entry.idempotencyKey(), entry.lines(), header.createdAt(), entry.reversesEntryId()));
    }

    @Override
    public Optional<JournalEntry> findByEntryId(UUID entryId) {
        return loadWhere(LOAD_BY_ENTRY_ID, entryId);
    }

    @Override
    public long balanceOf(String accountCode) {
        // An aggregate always yields exactly one row, and COALESCE makes its value non-null.
        return jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(el.amount_cents), 0)
                FROM entry_lines el
                JOIN accounts a ON a.id = el.account_id
                WHERE a.code = ?
                """,
                Long.class,
                accountCode);
    }

    /**
     * The sum of the lines of every entry recorded at or before {@code asOf}. A single statement, run with no
     * transaction around it: the as-of guarantee needs its snapshot to be newer than the {@link #databaseNow()}
     * read that settled {@code asOf} (see {@code SettleWindow}), and a transaction that began earlier would hold
     * an older one. The isolation level is not what matters here (a single statement behaves the same at any
     * level), so this is not pinned to one; {@code NEVER} makes a caller that wraps the read fail loudly instead
     * of silently weakening the answer. The instant is bound as a timestamptz; it carries at most the six
     * fractional digits the API accepts, so equality with a microsecond {@code created_at} is exact.
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public long balanceOf(String accountCode, Instant asOf) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(el.amount_cents), 0)
                FROM entry_lines el
                JOIN accounts a ON a.id = el.account_id
                JOIN journal_entries je ON je.id = el.journal_entry_id
                WHERE a.code = ? AND je.created_at <= ?
                """,
                Long.class,
                accountCode,
                OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));
    }

    /** {@code now()} in a statement of its own: the transaction start of that statement, i.e. the moment it ran. */
    @Override
    public Instant databaseNow() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    private Map<String, ResolvedAccount> resolveAccountsOrThrow(JournalEntry entry) {
        Set<String> codes = entry.lines().stream()
                .map(EntryLine::accountCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ResolvedAccount> resolved = resolveAccounts(codes);
        Map<String, ResolvedAccount> byCode =
                resolved.stream().collect(Collectors.toMap(ResolvedAccount::code, account -> account));

        Set<String> missing = new TreeSet<>(codes);
        missing.removeAll(byCode.keySet());
        if (!missing.isEmpty()) {
            throw new UnknownAccountException(missing);
        }

        validateSingleCurrency(resolved);
        return byCode;
    }

    private List<ResolvedAccount> resolveAccounts(Set<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        String placeholders = codes.stream().map(code -> "?").collect(Collectors.joining(","));
        String sql = "SELECT code, id, currency FROM accounts WHERE code IN (" + placeholders + ")";
        return jdbcTemplate.query(sql, this::mapResolvedAccount, codes.toArray());
    }

    static void validateSingleCurrency(List<ResolvedAccount> resolved) {
        Set<String> currencies = resolved.stream().map(ResolvedAccount::currency).collect(Collectors.toSet());
        if (currencies.size() > 1) {
            throw new MixedCurrencyException(currencies);
        }
    }

    /**
     * {@code DO NOTHING ... RETURNING} legitimately returns no row, hence {@code query} and not {@code queryForObject}.
     * If another transaction holds an uncommitted header for the key it waits: no row on commit, an insert on rollback.
     * Unlike a unique violation it leaves the transaction usable, and it needs only INSERT.
     *
     * <p>A reversal's header also carries the link. A retry of a successful reversal finds its key taken (no row); only a
     * <em>new</em> key against an already-reversed entry reaches the at-most-once constraint, whose unique violation is
     * matched by constraint name and thrown as {@link EntryAlreadyReversedException}. The transaction rolls back and the
     * rejected key stays unused. Any other unique violation propagates unchanged.
     */
    private Optional<InsertedHeader> insertHeaderIfAbsent(JournalEntry entry) {
        Long originalInternalId = entry.isReversal() ? internalIdOf(entry.reversesEntryId()) : null;
        try {
            List<InsertedHeader> inserted = jdbcTemplate.query(
                    """
                    INSERT INTO journal_entries (idempotency_key, reverses_entry_id)
                    VALUES (?, ?)
                    ON CONFLICT ON CONSTRAINT %s DO NOTHING
                    RETURNING id, entry_id, created_at
                    """
                            .formatted(IDEMPOTENCY_KEY_CONSTRAINT),
                    this::mapInsertedHeader,
                    entry.idempotencyKey(),
                    new SqlParameterValue(Types.BIGINT, originalInternalId));
            return inserted.stream().findFirst();
        } catch (DuplicateKeyException e) {
            if (violatesAtMostOnce(e)) {
                throw new EntryAlreadyReversedException(entry.reversesEntryId());
            }
            throw e;
        }
    }

    /**
     * The original's internal id, looked up on its own rather than inside the insert. Resolved by a
     * scalar subquery, an {@code entry_id} that matches nothing would yield {@code NULL} and the insert
     * would succeed as an <em>ordinary</em> entry carrying the negated lines.
     * Entries are never deleted and the use case has just loaded the original, so absence is a defect.
     */
    private long internalIdOf(UUID entryId) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM journal_entries WHERE entry_id = ?", Long.class, entryId);
        if (ids.isEmpty()) {
            throw new IllegalStateException("entry " + entryId + " is to be reversed but no stored entry was found");
        }
        return ids.get(0);
    }

    /** Whether the violated unique constraint is the at-most-once one, by name, as the server reported it. */
    static boolean violatesAtMostOnce(DuplicateKeyException e) {
        return e.getMostSpecificCause() instanceof PSQLException psql
                && psql.getServerErrorMessage() != null
                && REVERSES_ENTRY_CONSTRAINT.equals(psql.getServerErrorMessage().getConstraint());
    }

    private JournalEntry loadByKey(String idempotencyKey) {
        // Entries are append-only and a conflicting insert only gives up once the other transaction
        // has committed, so an empty result here is a defect, not a client error.
        return loadWhere(LOAD_BY_KEY, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key \"" + idempotencyKey + "\" conflicted but no stored entry was found"));
    }

    /**
     * Rebuilds the entry through the validating constructor: a stored entry is
     * re-checked against today's rules on every load. Empty when there are no rows, which means no
     * such entry (an entry cannot exist without lines: {@code LDG03}).
     */
    private Optional<JournalEntry> loadWhere(String sql, Object value) {
        List<StoredLine> rows = jdbcTemplate.query(sql, this::mapStoredLine, value);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredLine first = rows.get(0);
        List<EntryLine> lines = rows.stream().map(StoredLine::line).toList();
        return Optional.of(new JournalEntry(
                first.entryId(), first.idempotencyKey(), lines, first.createdAt(), first.reversesEntryId()));
    }

    private void insertLines(long journalEntryId, List<EntryLine> lines, Map<String, ResolvedAccount> byCode) {
        List<Object[]> batchArgs = new ArrayList<>();
        for (EntryLine line : lines) {
            batchArgs.add(new Object[] {
                journalEntryId, line.lineNumber(), byCode.get(line.accountCode()).accountId(), line.amountCents()
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, ?, ?, ?)",
                batchArgs);
    }

    private ResolvedAccount mapResolvedAccount(ResultSet rs, int rowNum) throws SQLException {
        return new ResolvedAccount(rs.getString("code"), rs.getLong("id"), rs.getString("currency"));
    }

    private InsertedHeader mapInsertedHeader(ResultSet rs, int rowNum) throws SQLException {
        return new InsertedHeader(
                rs.getLong("id"), (UUID) rs.getObject("entry_id"), rs.getTimestamp("created_at").toInstant());
    }

    private StoredLine mapStoredLine(ResultSet rs, int rowNum) throws SQLException {
        return new StoredLine(
                (UUID) rs.getObject("entry_id"),
                rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toInstant(),
                (UUID) rs.getObject("reverses_entry_id"),
                new EntryLine(rs.getInt("line_number"), rs.getString("code"), rs.getLong("amount_cents")));
    }

    record ResolvedAccount(String code, long accountId, String currency) {}

    private record InsertedHeader(long id, UUID entryId, Instant createdAt) {}

    private record StoredLine(
            UUID entryId, String idempotencyKey, Instant createdAt, UUID reversesEntryId, EntryLine line) {}
}
