package com.github.josemattioni.ledger_split_engine.application.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two threads reverse the same entry at the same moment, through the real use case and adapter, repeated
 * 20 times with a fresh original and fresh accounts each round. The probabilistic end-to-end counterpart of
 * {@code ReversalConcurrencyTest}, which pins the interleaving deterministically at SQL level: here the
 * interleaving is whatever the scheduler produces, and the invariant must hold for all of them.
 *
 * <p>There is no lock and no pre-check behind this: the key's unique index and the at-most-once constraint
 * decide, so with different keys exactly one wins and the other is refused, and with the same key the
 * loser is answered with the winner's reversal.
 */
@SpringBootTest
class ReversalRaceTest extends AbstractIntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    private PostJournalEntry postJournalEntry;

    @Autowired
    private ReverseJournalEntry reverseJournalEntry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** What one racing thread saw: a result, or the exception it threw. */
    private record Attempt(PostJournalEntryResult result, Throwable error) {}

    /** A freshly posted original on fresh accounts, so each round races over its own entry. */
    private JournalEntry freshOriginal() {
        String debit = registerAccount("seller_payable", AccountType.LIABILITY);
        String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
        JournalEntry request = new JournalEntry(
                null,
                uniqueIdempotencyKey(),
                List.of(new EntryLine(1, debit, 500L), new EntryLine(2, credit, -500L)),
                null);
        return ((PostJournalEntryResult.Created) postJournalEntry.execute(request)).entry();
    }

    /** Releases both reversals at once from a start latch and collects what each thread saw. */
    private List<Attempt> race(UUID target, String firstKey, String secondKey) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = List.of(
                executor.submit(() -> attempt(target, firstKey, ready, start)),
                executor.submit(() -> attempt(target, secondKey, ready, start)));
        assertThat(ready.await(30, TimeUnit.SECONDS)).as("both threads reached the start line").isTrue();
        start.countDown();
        // Not 30 s: a worker's transaction is ended by the server after 30 s, so give up first, with our own message.
        long deadline = CONCURRENCY_DEADLINE.toSeconds();
        return List.of(futures.get(0).get(deadline, TimeUnit.SECONDS), futures.get(1).get(deadline, TimeUnit.SECONDS));
    }

    private Attempt attempt(UUID target, String key, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new Attempt(reverseJournalEntry.execute(target, key), null);
        } catch (Throwable t) {
            return new Attempt(null, t);
        }
    }

    private static long count(List<Attempt> attempts, Class<? extends PostJournalEntryResult> type) {
        return attempts.stream().filter(a -> type.isInstance(a.result())).count();
    }

    private int reversalsOf(UUID originalEntryId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM journal_entries r
                JOIN journal_entries o ON o.id = r.reverses_entry_id
                WHERE o.entry_id = ?
                """,
                Integer.class,
                originalEntryId);
    }

    private static JournalEntry entryOf(PostJournalEntryResult result) {
        return switch (result) {
            case PostJournalEntryResult.Created created -> created.entry();
            case PostJournalEntryResult.Replayed replayed -> replayed.entry();
        };
    }

    @Test
    void twoSimultaneousReversalsUnderDifferentKeysCreateOneAndRefuseTheOther() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            JournalEntry original = freshOriginal();

            List<Attempt> attempts = race(original.entryId(), uniqueIdempotencyKey(), uniqueIdempotencyKey());

            String context = "round " + round + ", original " + original.entryId() + ", attempts " + attempts;
            assertThat(count(attempts, PostJournalEntryResult.Created.class)).as(context).isEqualTo(1);
            assertThat(attempts.stream().filter(a -> a.error() instanceof EntryAlreadyReversedException).count())
                    .as(context)
                    .isEqualTo(1);
            attempts.stream()
                    .filter(a -> a.error() != null)
                    .forEach(a -> assertThat(a.error()).as(context).hasMessageContaining(original.entryId().toString()));
            assertThat(reversalsOf(original.entryId())).as(context).isEqualTo(1);
        }
    }

    // The precedence of the idempotency key over the at-most-once constraint, under contention: a concurrent
    // retry (the same key for the same entry) is answered with the winner's reversal and is never refused as
    // "already reversed".
    @Test
    void twoSimultaneousRetriesUnderTheSameKeyCreateOneAndReplayTheOther() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            JournalEntry original = freshOriginal();
            String key = uniqueIdempotencyKey();

            List<Attempt> attempts = race(original.entryId(), key, key);

            String context = "round " + round + ", original " + original.entryId() + ", attempts " + attempts;
            assertThat(attempts).as(context).noneMatch(a -> a.error() != null);
            assertThat(count(attempts, PostJournalEntryResult.Created.class)).as(context).isEqualTo(1);
            assertThat(count(attempts, PostJournalEntryResult.Replayed.class)).as(context).isEqualTo(1);
            // The replay is the original reversal, not a copy: same entry_id and created_at.
            assertThat(attempts.stream().map(a -> entryOf(a.result())).distinct()).as(context).hasSize(1);
            assertThat(reversalsOf(original.entryId())).as(context).isEqualTo(1);
        }
    }
}
