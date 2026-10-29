package com.github.josemattioni.ledger_split_engine.domain.journalentry;

/**
 * What the store observed when asked to post an entry. The port reports this state; it does not
 * decide whether an {@link AlreadyExists} is a retry or a reuse of the key — the use case does.
 */
public sealed interface PostOutcome {

    /** The key was new: the entry was written, with its server-assigned fields filled in. */
    record Created(JournalEntry entry) implements PostOutcome {}

    /** The key was already used: {@code stored} is the entry that holds it, exactly as written. */
    record AlreadyExists(JournalEntry stored) implements PostOutcome {}
}
