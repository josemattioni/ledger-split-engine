package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The rule that makes an as-of balance stable. An entry's {@code created_at} is the start of its
 * transaction, but the entry becomes visible only when that transaction commits, so a balance as of an
 * instant that is too recent could still change. The system therefore answers only for an instant at
 * least {@link #WINDOW} before the database's own clock, and every transaction of the application's
 * database role is bounded to half of that: an entry recorded before the horizon has by then either
 * committed or been abandoned, and the other half is the margin (timer start, commit latency).
 *
 * <p>Pure: the clock values come from the caller (the database's, never the JVM's), and the database's
 * settings are read elsewhere and passed in.
 */
public final class SettleWindow {

    /** How far in the past an {@code as_of} must be before the system will answer for it. */
    public static final Duration WINDOW = Duration.ofSeconds(60);

    /** The longest transaction bound that still leaves the other half of the window as margin. */
    public static final Duration MAX_TRANSACTION_BOUND = WINDOW.dividedBy(2);

    private SettleWindow() {}

    /** Whether {@code asOf} is not later than {@code databaseNow} minus the window (inclusive). */
    public static boolean isSettled(Instant asOf, Instant databaseNow) {
        return !asOf.isAfter(databaseNow.minus(WINDOW));
    }

    /**
     * Everything wrong with the database settings the settle argument depends on, one message each, empty
     * when they are fine. All of them are reported so one start-up failure names everything to fix.
     *
     * @param timeoutMillis the application role's {@code transaction_timeout} in milliseconds, empty if
     *     the server has no such setting (it exists from PostgreSQL 17)
     * @param maxPreparedTransactions the server's {@code max_prepared_transactions}, empty if it could
     *     not be read. It must be 0: a prepared transaction leaves its session and can be committed much
     *     later while keeping the timestamp of its start, which no per-session bound can limit.
     */
    public static List<String> violationsOf(OptionalLong timeoutMillis, OptionalLong maxPreparedTransactions) {
        List<String> violations = new ArrayList<>();
        long maxMillis = MAX_TRANSACTION_BOUND.toMillis();
        if (timeoutMillis.isEmpty()) {
            violations.add("transaction_timeout does not exist on this database server: it needs PostgreSQL 17 or later");
        } else if (timeoutMillis.getAsLong() == 0) {
            violations.add("transaction_timeout is 0 (disabled: no bound is set) for the application's database role: it"
                    + " must be greater than 0 and at most " + MAX_TRANSACTION_BOUND.toSeconds() + " seconds");
        } else if (timeoutMillis.getAsLong() > maxMillis) {
            violations.add("transaction_timeout is " + timeoutMillis.getAsLong() + " ms for the application's database"
                    + " role: it must be at most " + MAX_TRANSACTION_BOUND.toSeconds() + " seconds (" + maxMillis + " ms)");
        }
        if (maxPreparedTransactions.isEmpty()) {
            violations.add("max_prepared_transactions could not be read: it must be 0");
        } else if (maxPreparedTransactions.getAsLong() != 0) {
            violations.add("max_prepared_transactions is " + maxPreparedTransactions.getAsLong()
                    + ": it must be 0, because a prepared transaction can be committed long after its created_at");
        }
        return violations;
    }
}
