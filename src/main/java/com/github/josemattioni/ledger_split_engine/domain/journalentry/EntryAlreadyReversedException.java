package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import java.util.UUID;

/**
 * The entry a client asked to reverse already has a reversal. A ledger rule (422), so it does not
 * extend {@link IllegalArgumentException}. Names the entry that was to be reversed.
 */
public class EntryAlreadyReversedException extends RuntimeException {

    public EntryAlreadyReversedException(UUID entryId) {
        super("entry " + entryId + " has already been reversed");
    }
}
