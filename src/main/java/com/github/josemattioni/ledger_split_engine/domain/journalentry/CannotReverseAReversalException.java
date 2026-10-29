package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import java.util.UUID;

/**
 * The entry a client asked to reverse is itself a reversal. A ledger rule (422), so it deliberately
 * does not extend {@link IllegalArgumentException}, which the web layer treats as a malformed request.
 */
public class CannotReverseAReversalException extends RuntimeException {

    public CannotReverseAReversalException(UUID entryId) {
        super("entry " + entryId + " is itself a reversal and cannot be reversed; "
                + "to undo it, post the original movement again as an ordinary entry");
    }
}
