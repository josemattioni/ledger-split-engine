package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import com.github.josemattioni.ledger_split_engine.adapter.web.InvalidRequestException;
import com.github.josemattioni.ledger_split_engine.adapter.web.RequestBodyTooLargeException;
import com.github.josemattioni.ledger_split_engine.adapter.web.journalentry.PostJournalEntryRequest.LineRequest;
import com.github.josemattioni.ledger_split_engine.application.journalentry.PostJournalEntry;
import com.github.josemattioni.ledger_split_engine.application.journalentry.PostJournalEntryResult;
import com.github.josemattioni.ledger_split_engine.application.journalentry.ReverseJournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.EchoedValue;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKey;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/journal-entries")
public class JournalEntryController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * The canonical text form of a UUID: 8-4-4-4-12 hexadecimal digits, either letter case (a UUID is
     * case-insensitive text). {@link UUID#fromString} alone also accepts forms like {@code 1-1-1-1-1},
     * which would turn a malformed id into a lookup of some other UUID.
     */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");

    private final PostJournalEntry postJournalEntry;
    private final ReverseJournalEntry reverseJournalEntry;

    public JournalEntryController(PostJournalEntry postJournalEntry, ReverseJournalEntry reverseJournalEntry) {
        this.postJournalEntry = postJournalEntry;
        this.reverseJournalEntry = reverseJournalEntry;
    }

    @PostMapping
    public ResponseEntity<JournalEntryResponse> post(
            @RequestHeader HttpHeaders headers, @Valid @RequestBody PostJournalEntryRequest request) {
        String idempotencyKey = idempotencyKeyFrom(headers);

        // Only building the entry from the request is guarded (a structural defect here is the client's
        // mistake, 400); the use case runs outside it, so its own failures stay internal errors.
        List<LineRequest> submitted = request.lines();
        JournalEntry entry = InvalidRequestException.guard(() -> {
            List<EntryLine> lines = IntStream.range(0, submitted.size())
                    .mapToObj(i -> new EntryLine(i + 1, submitted.get(i).accountCode(), submitted.get(i).amountCents()))
                    .toList();
            return new JournalEntry(null, idempotencyKey, lines, null);
        });

        return respondTo(postJournalEntry.execute(entry));
    }

    /**
     * Reverses the entry with this public {@code entry_id}. Everything the client can get wrong is checked
     * here, before the use case: the use case builds a {@code JournalEntry}, and a malformed key reaching
     * it would be an {@code IllegalArgumentException} outside request-to-domain construction, which is a
     * 500, not the client's 400.
     *
     * <p>A request body is rejected: the server builds the reversal's lines, and a body that was silently
     * ignored would answer a client that tried to send lines (a partial reversal) with a full reversal.
     */
    @PostMapping("/{entry_id}/reversal")
    public ResponseEntity<JournalEntryResponse> reverse(
            @PathVariable("entry_id") String entryId, @RequestHeader HttpHeaders headers, HttpServletRequest request)
            throws IOException {
        UUID target = InvalidRequestException.guard(() -> canonicalUuid(entryId));
        String idempotencyKey = idempotencyKeyFrom(headers);
        if (hasABody(request)) {
            throw new InvalidRequestException(
                    "a reversal request must not have a body: the server builds the reversal's lines");
        }

        return respondTo(reverseJournalEntry.execute(target, idempotencyKey));
    }

    /**
     * One byte is enough to tell: any byte at all means there is a body, however long it is, and the rest is never
     * read. A body declared larger than the limit still fails on this first read (413, see
     * {@code RequestBodyLimitFilter}); a body that declares no length is judged by its first byte instead of being
     * read up to the limit.
     */
    private static boolean hasABody(HttpServletRequest request) throws IOException {
        try {
            return request.getInputStream().read() != -1;
        } catch (RequestBodyTooLargeException e) {
            throw e;
        } catch (IOException e) {
            throw new InvalidRequestException("the request body could not be read");
        }
    }

    /**
     * The key, once, and well-formed. A String parameter would silently join repeated headers into
     * "k1,k2", defeating the key. The shape is checked here, in request-to-domain terms, so a malformed
     * key is a 400 on every endpoint before any use case runs.
     */
    private static String idempotencyKeyFrom(HttpHeaders headers) {
        List<String> keys = headers.get(IDEMPOTENCY_KEY_HEADER);
        int received = keys == null ? 0 : keys.size();
        if (received != 1) {
            throw new InvalidRequestException(
                    IDEMPOTENCY_KEY_HEADER + " header must be provided exactly once (received " + received + ")");
        }
        String key = keys.get(0);
        return InvalidRequestException.guard(() -> {
            IdempotencyKey.requireWellFormed(key);
            return key;
        });
    }

    private static UUID canonicalUuid(String text) {
        if (!CANONICAL_UUID.matcher(text).matches()) {
            throw new IllegalArgumentException(
                    "entry_id must be a UUID in its canonical form (8-4-4-4-12 hexadecimal digits): " + EchoedValue.quoted(text));
        }
        return UUID.fromString(text);
    }

    private static ResponseEntity<JournalEntryResponse> respondTo(PostJournalEntryResult result) {
        return switch (result) {
            case PostJournalEntryResult.Created created ->
                ResponseEntity.status(HttpStatus.CREATED).body(JournalEntryResponse.from(created.entry()));
            // A retry gets the original response back, marked so the client can tell it was replayed.
            case PostJournalEntryResult.Replayed replayed ->
                ResponseEntity.status(HttpStatus.CREATED)
                        .header(IDEMPOTENT_REPLAYED_HEADER, "true")
                        .body(JournalEntryResponse.from(replayed.entry()));
        };
    }
}
