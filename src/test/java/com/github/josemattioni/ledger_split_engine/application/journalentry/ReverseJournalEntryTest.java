package com.github.josemattioni.ledger_split_engine.application.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.CannotReverseAReversalException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKeyReusedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reversal use case in plain Java: the shared in-memory fake port and a real {@link PostJournalEntry}
 * over it, no Spring, no database. Replay, reuse and creation are {@code PostJournalEntry}'s existing
 * behavior; these tests pin that reversal goes through it and that the use case adds only the load and
 * the build.
 */
class ReverseJournalEntryTest {

    private FakeJournalEntryPort port;
    private PostJournalEntry postJournalEntry;
    private ReverseJournalEntry reverseJournalEntry;

    @BeforeEach
    void setUp() {
        port = new FakeJournalEntryPort();
        postJournalEntry = new PostJournalEntry(port);
        reverseJournalEntry = new ReverseJournalEntry(port, postJournalEntry);
    }

    private JournalEntry postOrdinary(String key, long cents) {
        JournalEntry request = new JournalEntry(
                null,
                key,
                List.of(new EntryLine(1, "seller_payable:1", cents), new EntryLine(2, "acquirer_fee", -cents)),
                null);
        return ((PostJournalEntryResult.Created) postJournalEntry.execute(request)).entry();
    }

    @Test
    void aMissingTargetIsNotFoundAndNothingIsWritten() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> reverseJournalEntry.execute(unknown, "reversal-key"))
                .isInstanceOf(JournalEntryNotFoundException.class)
                .hasMessageContaining(unknown.toString());

        assertThat(port.writes).isZero();
    }

    @Test
    void aReversalAsTheTargetIsRefusedAndNothingIsWritten() {
        JournalEntry original = postOrdinary("original-key", 100L);
        JournalEntry reversal =
                ((PostJournalEntryResult.Created) reverseJournalEntry.execute(original.entryId(), "reversal-key")).entry();

        assertThatThrownBy(() -> reverseJournalEntry.execute(reversal.entryId(), "another-key"))
                .isInstanceOf(CannotReverseAReversalException.class)
                .hasMessageContaining(reversal.entryId().toString());

        assertThat(port.writes).as("the original and the first reversal only").isEqualTo(2);
    }

    @Test
    void anOrdinaryTargetIsReversedThroughThePostPath() {
        JournalEntry original = postOrdinary("original-key", 100L);

        PostJournalEntryResult result = reverseJournalEntry.execute(original.entryId(), "reversal-key");

        assertThat(result).isInstanceOfSatisfying(PostJournalEntryResult.Created.class, created -> {
            // What the port was asked to write: the negated lines, linked to the original, under the given key.
            JournalEntry written = port.stored.get("reversal-key");
            assertThat(created.entry()).isEqualTo(written);
            assertThat(written.lines())
                    .containsExactly(new EntryLine(1, "seller_payable:1", -100L), new EntryLine(2, "acquirer_fee", 100L));
            assertThat(written.reversesEntryId()).isEqualTo(original.entryId());
        });
        assertThat(port.writes).isEqualTo(2);
    }

    @Test
    void theSameKeyAndTargetAgainIsReplayedAndWritesNothing() {
        JournalEntry original = postOrdinary("original-key", 100L);
        JournalEntry first =
                ((PostJournalEntryResult.Created) reverseJournalEntry.execute(original.entryId(), "reversal-key")).entry();

        PostJournalEntryResult retry = reverseJournalEntry.execute(original.entryId(), "reversal-key");

        assertThat(retry).isInstanceOfSatisfying(
                PostJournalEntryResult.Replayed.class, replayed -> assertThat(replayed.entry()).isEqualTo(first));
        assertThat(port.writes).as("the original and the one reversal").isEqualTo(2);
    }

    @Test
    void aKeyUsedByAnOrdinaryEntryIsAReuseAndWritesNothing() {
        JournalEntry original = postOrdinary("original-key", 100L);
        postOrdinary("taken-key", 7L);

        assertThatThrownBy(() -> reverseJournalEntry.execute(original.entryId(), "taken-key"))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasMessageContaining("taken-key");

        assertThat(port.writes).isEqualTo(2);
        assertThat(port.stored.values()).as("no reversal was stored").noneMatch(JournalEntry::isReversal);
    }

    @Test
    void aKeyUsedByTheReversalOfAnotherEntryIsAReuse() {
        JournalEntry a = postOrdinary("key-a", 100L);
        JournalEntry b = postOrdinary("key-b", 200L);
        reverseJournalEntry.execute(a.entryId(), "shared-key");

        assertThatThrownBy(() -> reverseJournalEntry.execute(b.entryId(), "shared-key"))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(port.stored.values().stream().filter(JournalEntry::isReversal)).hasSize(1);
    }

    // Identical lines, so only the link tells them apart: an ordinary post that carries a reversal's
    // exact lines is not that reversal's retry.
    @Test
    void aKeyUsedByAReversalCannotPostAnOrdinaryEntryWithTheSameLines() {
        JournalEntry original = postOrdinary("original-key", 100L);
        reverseJournalEntry.execute(original.entryId(), "shared-key");
        JournalEntry ordinaryWithTheReversalsLines = new JournalEntry(
                null,
                "shared-key",
                List.of(new EntryLine(1, "seller_payable:1", -100L), new EntryLine(2, "acquirer_fee", 100L)),
                null);

        assertThatThrownBy(() -> postJournalEntry.execute(ordinaryWithTheReversalsLines))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    // The at-most-once rule is the adapter's constraint, not a check here: a genuine second reversal is
    // refused by the port, the use case asks once more (see the next test), is refused again, and passes
    // the exception through. The key it was refused under stays unused.
    @Test
    void aSecondReversalUnderANewKeyIsRefusedByThePortAndPropagatesAfterTheSecondLook() {
        JournalEntry original = postOrdinary("original-key", 100L);
        reverseJournalEntry.execute(original.entryId(), "first-reversal-key");
        int callsBefore = port.insertOrFindCalls;

        assertThatThrownBy(() -> reverseJournalEntry.execute(original.entryId(), "second-reversal-key"))
                .isInstanceOf(EntryAlreadyReversedException.class)
                .hasMessageContaining(original.entryId().toString());

        assertThat(port.insertOrFindCalls - callsBefore).as("asked, then asked once more").isEqualTo(2);
        assertThat(port.stored).doesNotContainKey("second-reversal-key");
    }

    // A request with the SAME key in flight can make this one fail as "already reversed" even though it is
    // only a retry: both pass the key check before either is visible, and the reversal's unique index
    // objects first (observed on Postgres 16: it failed rounds 7-9 of 20 on every run). Asked again after
    // the winner has committed, the key check sees the winner, so the retry is answered with its reversal.
    @Test
    void aConcurrentRetryThatSurfacesAsTheConstraintIsAskedAgainAndReplayed() {
        JournalEntry original = postOrdinary("original-key", 100L);
        port.aConcurrentRequestWithTheSameKeyWinsTheNextReversal();

        PostJournalEntryResult result = reverseJournalEntry.execute(original.entryId(), "shared-key");

        assertThat(result).isInstanceOfSatisfying(
                PostJournalEntryResult.Replayed.class,
                replayed -> assertThat(replayed.entry()).isEqualTo(port.stored.get("shared-key")));
        assertThat(port.stored.values().stream().filter(JournalEntry::isReversal)).as("one reversal").hasSize(1);
    }
}
