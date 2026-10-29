package com.github.josemattioni.ledger_split_engine.application.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;

/**
 * What the {@link PostJournalEntry} use case decided. Distinct from the domain's {@code PostOutcome}:
 * the outcome is what the store observed, the result is what the use case concluded from it.
 */
public sealed interface PostJournalEntryResult {

    /** The key was new and the entry was written. */
    record Created(JournalEntry entry) implements PostJournalEntryResult {}

    /** The request was a retry of an entry already written; {@code entry} is that stored entry. */
    record Replayed(JournalEntry entry) implements PostJournalEntryResult {}
}
