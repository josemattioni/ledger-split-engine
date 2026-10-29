package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.account.AccountCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A journal entry. {@code reversesEntryId} is {@code null} for an ordinary entry; for a reversal it is
 * the public {@code entry_id} of the entry being reversed.
 */
public record JournalEntry(
        UUID entryId, String idempotencyKey, List<EntryLine> lines, Instant createdAt, UUID reversesEntryId) {

    public static final long MAX_ABS_AMOUNT_CENTS = 1_000_000_000_000L;

    /**
     * A policy value, like {@link #MAX_ABS_AMOUNT_CENTS}, so there is no database check behind it. 100 lines
     * covers a capture split across about 45 sellers; it also bounds the work done per entry (a
     * 100-parameter account lookup, and a balance trigger that re-sums the entry once per line).
     */
    public static final int MAX_LINES = 100;

    private static final int MIN_LINES = 2;

    /** An ordinary entry: it reverses nothing. */
    public JournalEntry(UUID entryId, String idempotencyKey, List<EntryLine> lines, Instant createdAt) {
        this(entryId, idempotencyKey, lines, createdAt, null);
    }

    public JournalEntry {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency_key must not be null");
        }
        if (lines == null || lines.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("lines must not be null and must not contain a null element");
        }
        lines = List.copyOf(lines);
        IdempotencyKey.requireWellFormed(idempotencyKey);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).lineNumber() != i + 1) {
                throw new IllegalArgumentException(
                        "line_number values must be exactly 1.." + lines.size() + " in order");
            }
        }

        // An entry must be a real movement of money. Checked in this order: the
        // two-line minimum first, so a lone line is reported as that and not as an imbalance.
        if (lines.size() < MIN_LINES) {
            throw DegenerateJournalEntryException.tooFewLines(lines.size());
        }
        if (lines.size() > MAX_LINES) {
            throw new LineLimitExceededException(lines.size());
        }
        // What each line names comes before what it carries. A code that cannot be a registered
        // account's is a ledger rule (422), checked here so it is reported whatever the key.
        for (EntryLine line : lines) {
            if (!AccountCode.isWellFormed(line.accountCode())) {
                throw new InvalidAccountCodeException(line.lineNumber(), line.accountCode());
            }
        }
        for (EntryLine line : lines) {
            if (line.amountCents() == 0) {
                throw DegenerateJournalEntryException.zeroAmount(line.lineNumber());
            }
        }
        // account_code is the accounts table's natural key, so equal strings are the same account.
        Map<String, Integer> firstLineByAccount = new HashMap<>();
        for (EntryLine line : lines) {
            Integer firstLine = firstLineByAccount.putIfAbsent(line.accountCode(), line.lineNumber());
            if (firstLine != null) {
                throw DegenerateJournalEntryException.repeatedAccount(
                        line.accountCode(), firstLine, line.lineNumber());
            }
        }

        for (EntryLine line : lines) {
            // Compared directly: Math.abs(Long.MIN_VALUE) is itself negative.
            if (line.amountCents() > MAX_ABS_AMOUNT_CENTS || line.amountCents() < -MAX_ABS_AMOUNT_CENTS) {
                throw new AmountLimitExceededException(line.lineNumber(), line.amountCents());
            }
        }

        long total = 0;
        try {
            for (EntryLine line : lines) {
                total = Math.addExact(total, line.amountCents());
            }
        } catch (ArithmeticException e) {
            throw new UnbalancedJournalEntryException(
                    "journal entry lines' amounts overflow the supported range when summed");
        }
        if (total != 0) {
            throw new UnbalancedJournalEntryException("journal entry lines sum to " + total + ", not 0");
        }
    }

    /**
     * The reversal of {@code original}: its lines at the same {@code line_number} and
     * {@code account_code} with each amount negated, linked to it, under {@code idempotencyKey}. Pure:
     * the server builds the lines, the client never sends them. {@code entryId} and {@code createdAt}
     * are left for the store to assign.
     *
     * <p>The original already satisfied every rule and the per-line cap is symmetric, so the negation
     * does too; the result still goes through the validating constructor.
     *
     * @throws CannotReverseAReversalException if {@code original} is itself a reversal
     * @throws IllegalArgumentException if {@code original} has no {@code entryId} (it was never
     *     stored: a defect, not a client error) or {@code idempotencyKey} is malformed
     */
    public static JournalEntry reversalOf(JournalEntry original, String idempotencyKey) {
        if (original.entryId() == null) {
            throw new IllegalArgumentException("only a stored entry can be reversed: it has no entry_id");
        }
        if (original.isReversal()) {
            throw new CannotReverseAReversalException(original.entryId());
        }
        // negateExact, not "-": a stored amount that somehow broke the cap fails loudly instead of wrapping.
        List<EntryLine> negated = original.lines().stream()
                .map(line -> new EntryLine(line.lineNumber(), line.accountCode(), Math.negateExact(line.amountCents())))
                .toList();
        return new JournalEntry(null, idempotencyKey, negated, null, original.entryId());
    }

    public boolean isReversal() {
        return reversesEntryId != null;
    }

    /**
     * Whether {@code other} asks for the same thing as this entry: the same lines, in the same
     * order, reversing the same entry (or neither reversing anything). {@code line_number} comes
     * from position, so a reordering is a different request. The link is compared because identical
     * lines do not make identical requests: an ordinary entry that carries a reversal's lines is not
     * that reversal. Server-assigned fields ({@code entryId}, {@code createdAt}) and the key are
     * not compared. Any future field that determines what is stored must be added here, and
     * persisted, or a retry with a different value would replay as if identical.
     */
    public boolean sameRequestAs(JournalEntry other) {
        return lines.equals(other.lines) && Objects.equals(reversesEntryId, other.reversesEntryId);
    }
}
