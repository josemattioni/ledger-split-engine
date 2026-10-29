package com.github.josemattioni.ledger_split_engine.domain.journalentry;

/**
 * An as-of balance was asked for an instant that is not yet settled: later than the database's current time
 * minus {@link SettleWindow#WINDOW}, which includes any instant in the future. Within the window an entry
 * could still commit with a {@code created_at} before the instant, so the answer could change. Answered with
 * 422; it does not extend {@link IllegalArgumentException}, which the web layer treats as a malformed request.
 */
public class AsOfNotSettledException extends RuntimeException {

    public AsOfNotSettledException() {
        super("as_of must be at least " + SettleWindow.WINDOW.toSeconds() + " seconds before the database's current time");
    }
}
