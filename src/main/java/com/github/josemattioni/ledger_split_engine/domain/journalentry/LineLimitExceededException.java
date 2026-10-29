package com.github.josemattioni.ledger_split_engine.domain.journalentry;

/**
 * A journal entry with more than {@link JournalEntry#MAX_LINES} lines. A ledger rule (422), so it
 * deliberately does not extend {@link IllegalArgumentException}, which the web layer treats as a
 * malformed request.
 */
public class LineLimitExceededException extends RuntimeException {

    public LineLimitExceededException(int lineCount) {
        super("journal entry has " + lineCount + " lines; at most " + JournalEntry.MAX_LINES + " are permitted");
    }
}
