package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import java.util.UUID;

/**
 * No journal entry has the requested public {@code entry_id}. Answered with 404; it does not extend
 * {@link IllegalArgumentException}, which the web layer treats as a malformed request.
 */
public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(UUID entryId) {
        super("No journal entry with entry_id \"" + entryId + "\"");
    }
}
