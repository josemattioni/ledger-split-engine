package com.github.josemattioni.ledger_split_engine.application.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKeyReusedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryPort;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import org.springframework.stereotype.Service;

/**
 * Posts a journal entry, deciding what an already-used idempotency key means. The port only reports
 * whether the key was new; this is where "retry" is told apart from "reuse".
 *
 * <p>Deliberately not {@code @Transactional}: the adapter's {@code insertOrFind} owns the
 * transaction (and pins its isolation level), and the comparison below needs no transaction because
 * a stored entry is immutable.
 */
@Service
public class PostJournalEntry {

    private final JournalEntryPort journalEntryPort;

    public PostJournalEntry(JournalEntryPort journalEntryPort) {
        this.journalEntryPort = journalEntryPort;
    }

    /**
     * @throws IdempotencyKeyReusedException if the key already identifies an entry whose lines differ
     */
    public PostJournalEntryResult execute(JournalEntry request) {
        return switch (journalEntryPort.insertOrFind(request)) {
            case PostOutcome.Created created -> new PostJournalEntryResult.Created(created.entry());
            case PostOutcome.AlreadyExists existing -> {
                if (!existing.stored().sameRequestAs(request)) {
                    throw new IdempotencyKeyReusedException(request.idempotencyKey());
                }
                yield new PostJournalEntryResult.Replayed(existing.stored());
            }
        };
    }
}
