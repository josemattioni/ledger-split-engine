package com.github.josemattioni.ledger_split_engine.application.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKeyReusedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The use case in plain Java: the shared in-memory fake port, no Spring, no database. */
class PostJournalEntryTest {

    private static final String KEY = "sale-4711";

    private FakeJournalEntryPort port;
    private PostJournalEntry postJournalEntry;

    @BeforeEach
    void setUp() {
        port = new FakeJournalEntryPort();
        postJournalEntry = new PostJournalEntry(port);
    }

    @Test
    void aNewKeyIsCreated() {
        JournalEntry request = entryOf(KEY, 100L);

        PostJournalEntryResult result = postJournalEntry.execute(request);

        assertThat(result).isInstanceOfSatisfying(PostJournalEntryResult.Created.class, created -> {
            assertThat(created.entry().entryId()).isNotNull();
            assertThat(created.entry().idempotencyKey()).isEqualTo(KEY);
            assertThat(created.entry().lines()).isEqualTo(request.lines());
        });
        assertThat(port.writes).isEqualTo(1);
    }

    @Test
    void aRetryWithTheSameLinesIsReplayedAsTheStoredEntryAndWritesNothing() {
        JournalEntry first = ((PostJournalEntryResult.Created) postJournalEntry.execute(entryOf(KEY, 100L))).entry();

        PostJournalEntryResult result = postJournalEntry.execute(entryOf(KEY, 100L));

        assertThat(result).isInstanceOfSatisfying(PostJournalEntryResult.Replayed.class, replayed ->
                // The stored entry itself, with its original server-assigned fields.
                assertThat(replayed.entry()).isEqualTo(first));
        assertThat(port.writes).as("entries written, retry included").isEqualTo(1);
    }

    @Test
    void aKeyReusedWithDifferentLinesIsRejectedNamingTheKeyAndWritesNothing() {
        JournalEntry first = ((PostJournalEntryResult.Created) postJournalEntry.execute(entryOf(KEY, 100L))).entry();

        assertThatThrownBy(() -> postJournalEntry.execute(entryOf(KEY, 150L)))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasMessageContaining(KEY);

        assertThat(port.writes).as("entries written, reuse included").isEqualTo(1);
        assertThat(port.stored.get(KEY)).as("the stored entry is untouched").isEqualTo(first);
    }

    private static JournalEntry entryOf(String key, long cents) {
        return new JournalEntry(
                null,
                key,
                List.of(new EntryLine(1, "seller_payable:1", cents), new EntryLine(2, "acquirer_fee", -cents)),
                null);
    }
}
