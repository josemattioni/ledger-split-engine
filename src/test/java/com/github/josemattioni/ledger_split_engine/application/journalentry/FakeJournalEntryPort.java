package com.github.josemattioni.ledger_split_engine.application.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryPort;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A hand-written in-memory store with the port's contract, for the use-case tests (no Spring, no
 * database). A new key is written and reported {@code Created}; an existing key reports the stored entry
 * without comparing it with the request. Like the real adapter it refuses a second reversal of one entry
 * under a new key, but only <em>after</em> the key lookup, so a retry under the same key reports the
 * existing reversal instead.
 */
final class FakeJournalEntryPort implements JournalEntryPort {

    /** Stored entries by idempotency key, in insertion order. */
    final Map<String, JournalEntry> stored = new LinkedHashMap<>();

    /** How many entries were actually written (a replay or a reuse writes nothing). */
    int writes;

    /** How many times the use case asked the port to insert-or-find, replays and refusals included. */
    int insertOrFindCalls;

    private boolean concurrentWinnerOnNextReversal;

    /** What {@link #databaseNow()} reports; a test sets it to control the clock. */
    Instant databaseNow = Instant.parse("2026-10-22T15:00:00Z");

    /** How many times the use case read the database clock. */
    int clockReads;

    /** Returned by {@link #balanceOf(String)}, the current balance. */
    long currentBalance;

    /** How many times the current balance was asked for. */
    int currentBalanceCalls;

    /** Returned by {@link #balanceOf(String, Instant)}, whatever the instant. */
    long asOfBalance;

    /** The instants the as-of balance was asked for, in order. */
    final List<Instant> asOfRequests = new ArrayList<>();

    /**
     * The next insert of a reversal under a new key behaves as it can against the real database when a
     * request with the <em>same</em> key is in flight: that request commits first, and this one is reported
     * as the at-most-once violation, although it is only a retry. The winner is stored under this very key.
     */
    void aConcurrentRequestWithTheSameKeyWinsTheNextReversal() {
        concurrentWinnerOnNextReversal = true;
    }

    @Override
    public PostOutcome insertOrFind(JournalEntry entry) {
        insertOrFindCalls++;
        JournalEntry existing = stored.get(entry.idempotencyKey());
        if (existing != null) {
            return new PostOutcome.AlreadyExists(existing);
        }
        if (entry.isReversal() && concurrentWinnerOnNextReversal) {
            concurrentWinnerOnNextReversal = false;
            write(entry);
            throw new EntryAlreadyReversedException(entry.reversesEntryId());
        }
        if (entry.isReversal()
                && stored.values().stream().anyMatch(other -> entry.reversesEntryId().equals(other.reversesEntryId()))) {
            throw new EntryAlreadyReversedException(entry.reversesEntryId());
        }
        return new PostOutcome.Created(write(entry));
    }

    private JournalEntry write(JournalEntry entry) {
        JournalEntry written = new JournalEntry(
                UUID.randomUUID(), entry.idempotencyKey(), entry.lines(), Instant.now(), entry.reversesEntryId());
        stored.put(entry.idempotencyKey(), written);
        writes++;
        return written;
    }

    @Override
    public Optional<JournalEntry> findByEntryId(UUID entryId) {
        return stored.values().stream().filter(entry -> entryId.equals(entry.entryId())).findFirst();
    }

    @Override
    public long balanceOf(String accountCode) {
        currentBalanceCalls++;
        return currentBalance;
    }

    @Override
    public long balanceOf(String accountCode, Instant asOf) {
        asOfRequests.add(asOf);
        return asOfBalance;
    }

    @Override
    public Instant databaseNow() {
        clockReads++;
        return databaseNow;
    }
}
