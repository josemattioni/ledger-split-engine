package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryPort {

    /**
     * Writes the entry if its idempotency key is new, otherwise reports the entry that already holds
     * the key. Reports state only: it does not compare the stored entry with the request.
     */
    PostOutcome insertOrFind(JournalEntry entry) throws UnknownAccountException, MixedCurrencyException;

    /**
     * The stored entry with this public {@code entry_id}, lines in {@code line_number} order, or empty
     * if there is none. For a reversal, {@code reversesEntryId} is the original's public id.
     */
    Optional<JournalEntry> findByEntryId(UUID entryId);

    /** The account's current balance: the signed sum of every line posted so far. */
    long balanceOf(String accountCode);

    /**
     * The account's balance as of {@code asOf}: the signed sum of the lines of every entry recorded
     * ({@code created_at}) at or before it, inclusive, at microsecond precision. Time is by {@code created_at},
     * never by internal id.
     *
     * <p>Only meaningful for a settled instant, and only if its snapshot is newer than the {@link #databaseNow()}
     * read that settled it (see {@link SettleWindow}); an implementation must therefore refuse to run inside a
     * transaction that could have started earlier.
     */
    long balanceOf(String accountCode, Instant asOf);

    /** The database's own clock, the one that stamps {@code created_at}: never the application's. */
    Instant databaseNow();
}
