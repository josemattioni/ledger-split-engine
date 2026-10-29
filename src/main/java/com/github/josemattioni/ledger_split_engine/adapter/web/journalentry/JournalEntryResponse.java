package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An entry as the API returns it. {@code reversesEntryId} is always present: {@code null} for an
 * ordinary entry, and for a reversal the public {@code entry_id} of the entry it reverses.
 */
public record JournalEntryResponse(
        UUID entryId,
        String idempotencyKey,
        Instant createdAt,
        UUID reversesEntryId,
        List<EntryLineResponse> lines) {

    static JournalEntryResponse from(JournalEntry entry) {
        return new JournalEntryResponse(
                entry.entryId(),
                entry.idempotencyKey(),
                entry.createdAt(),
                entry.reversesEntryId(),
                entry.lines().stream().map(EntryLineResponse::from).toList());
    }

    public record EntryLineResponse(int lineNumber, String accountCode, long amountCents) {

        static EntryLineResponse from(EntryLine line) {
            return new EntryLineResponse(line.lineNumber(), line.accountCode(), line.amountCents());
        }
    }
}
