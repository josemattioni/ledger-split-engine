package com.github.josemattioni.ledger_split_engine.application.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKeyReusedException;
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
 * Two threads post the same {@code Idempotency-Key} at the same moment, through the real use case
 * and adapter, repeated 20 times with a fresh key and fresh accounts each round. This is the
 * probabilistic end-to-end counterpart of {@code IdempotentInsertConcurrencyTest}, which pins the
 * interleaving deterministically at SQL level; here the interleaving is whatever the scheduler
 * produces, and the invariant must hold for all of them.
 */
@SpringBootTest
class IdempotentPostRaceTest extends AbstractIntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    private PostJournalEntry postJournalEntry;

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

    private static JournalEntry request(String key, String debit, String credit, long cents) {
        return new JournalEntry(
                null, key, List.of(new EntryLine(1, debit, cents), new EntryLine(2, credit, -cents)), null);
    }

    /** Releases both requests at once from a start latch and collects what each thread saw. */
    private List<Attempt> race(JournalEntry first, JournalEntry second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = List.of(
                executor.submit(() -> attempt(first, ready, start)), executor.submit(() -> attempt(second, ready, start)));
        assertThat(ready.await(30, TimeUnit.SECONDS)).as("both threads reached the start line").isTrue();
        start.countDown();
        // Not 30 s: a worker's transaction is ended by the server after 30 s, so give up first, with our own message.
        long deadline = CONCURRENCY_DEADLINE.toSeconds();
        return List.of(futures.get(0).get(deadline, TimeUnit.SECONDS), futures.get(1).get(deadline, TimeUnit.SECONDS));
    }

    private Attempt attempt(JournalEntry request, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new Attempt(postJournalEntry.execute(request), null);
        } catch (Throwable t) {
            return new Attempt(null, t);
        }
    }

    private static long count(List<Attempt> attempts, Class<? extends PostJournalEntryResult> type) {
        return attempts.stream().filter(a -> type.isInstance(a.result())).count();
    }

    private int headerCount(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, key);
    }

    private List<EntryLine> storedLines(String key) {
        return jdbcTemplate.query(
                """
                SELECT el.line_number, a.code, el.amount_cents
                FROM entry_lines el
                JOIN journal_entries je ON je.id = el.journal_entry_id
                JOIN accounts a ON a.id = el.account_id
                WHERE je.idempotency_key = ?
                ORDER BY el.line_number
                """,
                (rs, rowNum) -> new EntryLine(rs.getInt("line_number"), rs.getString("code"), rs.getLong("amount_cents")),
                key);
    }

    @Test
    void twoSimultaneousIdenticalPostsCreateOneEntryAndReplayTheOther() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            String debit = registerAccount("seller_payable", AccountType.LIABILITY);
            String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
            String key = "race-same-" + UUID.randomUUID();

            List<Attempt> attempts = race(request(key, debit, credit, 100L), request(key, debit, credit, 100L));

            String context = "round " + round + ", key " + key + ", attempts " + attempts;
            assertThat(count(attempts, PostJournalEntryResult.Created.class)).as(context).isEqualTo(1);
            assertThat(count(attempts, PostJournalEntryResult.Replayed.class)).as(context).isEqualTo(1);
            // The replay is the original entry, not a copy: same entry_id and created_at.
            assertThat(attempts.stream().map(a -> entryOf(a.result())).distinct())
                    .as(context)
                    .hasSize(1);
            assertThat(headerCount(key)).as(context).isEqualTo(1);
            assertThat(storedLines(key)).as(context).hasSize(2);
        }
    }

    @Test
    void twoSimultaneousPostsWithDifferentLinesCreateOneEntryAndRejectTheOther() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            String debit = registerAccount("seller_payable", AccountType.LIABILITY);
            String credit = registerAccount("acquirer_fee", AccountType.EXPENSE);
            String key = "race-different-" + UUID.randomUUID();

            List<Attempt> attempts = race(request(key, debit, credit, 100L), request(key, debit, credit, 150L));

            String context = "round " + round + ", key " + key + ", attempts " + attempts;
            assertThat(count(attempts, PostJournalEntryResult.Created.class)).as(context).isEqualTo(1);
            assertThat(attempts.stream().filter(a -> a.error() instanceof IdempotencyKeyReusedException).count())
                    .as(context)
                    .isEqualTo(1);
            assertThat(headerCount(key)).as(context).isEqualTo(1);
            // Whoever won, its lines are what is stored; the loser left nothing behind.
            JournalEntry winner = attempts.stream()
                    .filter(a -> a.result() instanceof PostJournalEntryResult.Created)
                    .map(a -> entryOf(a.result()))
                    .findFirst()
                    .orElseThrow();
            assertThat(storedLines(key)).as(context).isEqualTo(winner.lines());
        }
    }

    private static JournalEntry entryOf(PostJournalEntryResult result) {
        return switch (result) {
            case PostJournalEntryResult.Created created -> created.entry();
            case PostJournalEntryResult.Replayed replayed -> replayed.entry();
        };
    }
}
