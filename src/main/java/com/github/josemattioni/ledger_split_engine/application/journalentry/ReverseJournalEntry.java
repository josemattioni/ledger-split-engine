package com.github.josemattioni.ledger_split_engine.application.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.CannotReverseAReversalException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKeyReusedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryPort;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reverses a posted entry: loads it, builds the reversal in the domain, and posts it through {@link PostJournalEntry},
 * so replay, reuse and creation are that use case's behavior and are not duplicated here.
 *
 * <p>Deliberately not {@code @Transactional}, and deliberately with no "is it already reversed?" check. The target is
 * immutable, so reading it outside the write's transaction is safe, and "at most once" is a database constraint, not
 * a lock ({@code ledger_app} cannot take row locks). A pre-check would be wrong twice: two concurrent requests would
 * both pass it, and a retry of a successful reversal would find its own reversal and be refused instead of replayed.
 */
@Service
public class ReverseJournalEntry {

    private final JournalEntryPort journalEntryPort;
    private final PostJournalEntry postJournalEntry;

    public ReverseJournalEntry(JournalEntryPort journalEntryPort, PostJournalEntry postJournalEntry) {
        this.journalEntryPort = journalEntryPort;
        this.postJournalEntry = postJournalEntry;
    }

    /**
     * @param entryId the public {@code entry_id} of the entry to reverse
     * @param idempotencyKey the key of this reversal; already validated by the caller
     * @throws JournalEntryNotFoundException if no entry has that id
     * @throws CannotReverseAReversalException if that entry is itself a reversal
     * @throws IdempotencyKeyReusedException if the key already identifies a different request
     * @throws EntryAlreadyReversedException if the entry already has a reversal and the key is new
     */
    public PostJournalEntryResult execute(UUID entryId, String idempotencyKey) {
        JournalEntry original =
                journalEntryPort.findByEntryId(entryId).orElseThrow(() -> new JournalEntryNotFoundException(entryId));
        JournalEntry reversal = JournalEntry.reversalOf(original, idempotencyKey);
        try {
            return postJournalEntry.execute(reversal);
        } catch (EntryAlreadyReversedException firstLook) {
            // A same-key request still in flight can surface as this exception although this one is only a retry: the
            // reversal's unique index can raise before the key conflict resolves. Asked again, the other request has
            // committed, so the key check sees it and replays (or reports a reuse); a genuinely new key is refused the same
            // way and the exception propagates. The retry is a fresh transaction, so it lives here and not in the adapter.
            // ReversalRaceTest.twoSimultaneousRetriesUnderTheSameKeyCreateOneAndReplayTheOther reproduces it.
            return postJournalEntry.execute(reversal);
        }
    }
}
