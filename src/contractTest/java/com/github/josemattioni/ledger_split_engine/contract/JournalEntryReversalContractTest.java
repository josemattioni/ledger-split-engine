package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box contract for the entry-reversal slice, written from
 * openspec/changes/archive/2026-10-21-entry-reversal/specs/journal-entry-reversal/spec.md, the journal-entries
 * delta in openspec/changes/archive/2026-10-21-entry-reversal/specs/journal-entries/spec.md (both now synced
 * into openspec/specs), and PROJECT-CONVENTIONS.md only.
 * Endpoint: {@code POST /api/v1/journal-entries/{entry_id}/reversal}, no body, {@code Idempotency-Key}
 * header.
 *
 * <p>Requirement slugs used in the display names (journal-entry-reversal):
 * <ul>
 *   <li>{@code reverse-in-full}: "A posted entry can be reversed in full"
 *   <li>{@code reversal-restores-balances}: "A reversal restores balances like any other entry"
 *   <li>{@code reversal-leaves-original}: "A reversal never changes the original entry"
 *   <li>{@code reversal-has-no-body}: "The reversal request carries no body"
 *   <li>{@code reverse-at-most-once}: "An entry can be reversed at most once"
 *   <li>{@code no-reversal-of-a-reversal}: "A reversal cannot itself be reversed"
 *   <li>{@code entry-to-reverse-exists}: "The entry to reverse must exist and be identified by a
 *       well-formed entry_id"
 *   <li>{@code reversal-key-required}: "A reversal requires an idempotency key"
 *   <li>{@code reversal-retry-replayed}: "A retried reversal is replayed"
 *   <li>{@code reversal-key-reuse-rejected}: "A key reused for a different request is rejected for
 *       reversals"
 *   <li>{@code reversal-check-order}: "A reversal request is evaluated in a fixed order"
 *   <li>{@code concurrent-reversals-one}: "Concurrent reversals of one entry produce exactly one
 *       reversal"
 *   <li>{@code rejected-reversal-keeps-key}: "A rejected reversal does not consume its key"
 *   <li>{@code reversal-errors-problem-json}: "Reversal errors are reported as problem+json"
 * </ul>
 * and, for the modified journal-entries requirements: {@code post-balanced-journal-entry},
 * {@code idempotency-keys-unique}, {@code server-assigned-identity}, {@code ledger-rule-problem-types},
 * {@code backstop-internal-error}, {@code key-reuse-rejected}.
 *
 * <p>Every account is fresh, so "creates nothing" is observed through balances. Where the spec
 * says "the entry is not reversed", the test also reverses it successfully afterwards, which an
 * entry that already had a reversal would refuse. "The key is unused" is observed by using it
 * successfully afterwards without an {@code Idempotent-Replayed} header.
 *
 * <p>Tests whose scenario the spec leaves open accept every defensible outcome, still forbid 500s
 * and silent corruption, and print an {@code OBSERVED} line with what actually happened.
 */
class JournalEntryReversalContractTest extends JournalSliceContractSupport {

    static final String REVERSAL = ENTRIES + "/{entry_id}/reversal";
    /** 8-4-4-4-12 hexadecimal digits, either letter case: the spec's canonical form. */
    static final String CANONICAL = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    /** A stored amount whose digits cannot plausibly occur in a key, code or wording by accident. */
    static final long STORED = 48_271_939L;

    // ------------------------------------------------------------------ reverse-in-full

    @Test
    @DisplayName("REQ: reverse-in-full — reversing a two-line entry with an unused key is 201: lines negated at the same line_number and account_code, reverses_entry_id = the original's entry_id, idempotency_key = the key sent, its own entry_id")
    void successfulReversal() {
        Posted x = postTwoLines(1500);
        String key = newKey();

        Response r = reverse(x.id(), key);

        assertReversalOf(r, x, key);
        assertNotReplayed(r);
    }

    @Test
    @DisplayName("REQ: reverse-in-full — an entry of +9750, -750, -9000 is reversed as -9750, +750, +9000 on the same accounts, in the same order, numbered 1, 2, 3")
    void threeLinesNegatedInOrder() {
        Posted x = postEntry(newAccounts(3), cents(9750, -750, -9000));
        String key = newKey();

        Response r = reverse(x.id(), key);

        assertReversalOf(r, x, key);
        assertLines(r, x.codes(), cents(-9750, 750, 9000));
    }

    @Test
    @DisplayName("REQ: reverse-in-full — a nine-line entry is reversed line for line in submitted order (not sorted by amount or account)")
    void nineLinesNegatedInOrder() {
        Posted x = postEntry(newAccounts(9), cents(5, -1, 7, -3, 2, -9, 4, -6, 1));
        String key = newKey();

        assertReversalOf(reverse(x.id(), key), x, key);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reverse-in-full — a 40-line entry on 40 distinct accounts is reversed in full")
    void fortyLinesReversed() {
        Posted x = postEntry(newAccounts(40), pairs(20));
        String key = newKey();

        assertReversalOf(reverse(x.id(), key), x, key);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reverse-in-full — an entry at the permitted amount limit [L, -L] is reversed as exactly [-L, L]")
    void reversalAtAmountLimit() {
        BigInteger limit = permittedLimit();
        Posted x = postEntry(List.of(newAccount("ASSET"), newAccount("LIABILITY")), List.of(limit, limit.negate()));
        String key = newKey();

        assertReversalOf(reverse(x.id(), key), x, key);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reverse-in-full — a reversal has its own entry_id and created_at; created_at is an ISO-8601 timestamp with offset, close to now, not earlier than the original's")
    void reversalHasOwnIdentityAndTimestamp() {
        Instant before = Instant.now().minus(2, ChronoUnit.MINUTES);
        Posted x = postTwoLines(320);
        Response r = reverseOk(x.id());
        Instant after = Instant.now().plus(2, ChronoUnit.MINUTES);

        assertThat(r.jsonPath().getString("entry_id")).as("reversal entry_id").isNotBlank().isNotEqualTo(x.id());
        Instant original = timestamp(x.response());
        Instant reversal = timestamp(r);
        observe("created_at original vs reversal", 201, "", original + " -> " + reversal);
        assertThat(reversal).as("reversal created_at").isBetween(before, after);
        assertThat(reversal).as("a reversal's created_at is not earlier than the original's").isAfterOrEqualTo(original);
    }

    @Test
    @DisplayName("REQ: reverse-in-full — a reversal is returned in the same representation as a posted entry: application/json, the same members at the top level and in every line")
    void reversalSameRepresentationAsPost() {
        Posted x = postTwoLines(250);

        Response r = reverseOk(x.id());

        assertThat(r.contentType()).as("Content-Type of a reversal").startsWith("application/json");
        assertThat(r.jsonPath().getMap("").keySet())
                .as("reversal members vs posted-entry members (%s vs %s)", r.asString(), x.response().asString())
                .isEqualTo(x.response().jsonPath().getMap("").keySet());
        List<Map<String, Object>> posted = x.response().jsonPath().getList("lines");
        List<Map<String, Object>> reversed = r.jsonPath().getList("lines");
        for (int i = 0; i < posted.size(); i++) {
            assertThat(reversed.get(i).keySet()).as("members of line %d", i + 1).isEqualTo(posted.get(i).keySet());
        }
    }

    @Test
    @DisplayName("REQ: reverse-in-full — a reversal's keys are snake_case at every level, amount_cents and line_number are JSON integers, and reverses_entry_id is a canonical UUID string")
    void reversalBodyShape() {
        Posted x = postTwoLines(123456789L);

        Response r = reverseOk(x.id());

        assertSnakeCaseKeys(r.jsonPath().getMap(""), "reversal");
        List<Map<String, Object>> lines = r.jsonPath().getList("lines");
        for (Map<String, Object> l : lines) {
            assertSnakeCaseKeys(l, "reversal line");
            integer(l.get("amount_cents"), "amount_cents");
            integer(l.get("line_number"), "line_number");
        }
        Object link = r.jsonPath().get("reverses_entry_id");
        assertThat(link).as("reverses_entry_id must be a JSON string").isInstanceOf(String.class);
        assertThat((String) link).as("reverses_entry_id").matches(CANONICAL);
    }

    @Test
    @DisplayName("REQ: reverse-in-full — reversing an older entry negates only that entry's lines: a later entry on the same accounts still counts")
    void reversalNegatesOnlyItsEntry() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Posted x = postEntry(List.of(a, b), cents(500, -500));
        postOk(entry(line(a, 200), line(b, -200)));
        String key = newKey();

        assertReversalOf(reverse(x.id(), key), x, key);

        assertBalance(a, 200);
        assertBalance(b, -200);
    }

    @Test
    @DisplayName("REQ: reverse-in-full — two entries with identical lines are two entries: each can be reversed once, each reversal linked to its own original")
    void identicalEntriesReversedSeparately() {
        List<String> codes = List.of(newAccount("ASSET"), newAccount("LIABILITY"));
        Posted x = postEntry(codes, cents(300, -300));
        Posted y = postEntry(codes, cents(300, -300));
        String kx = newKey();
        String ky = newKey();

        assertReversalOf(reverse(x.id(), kx), x, kx);
        assertReversalOf(reverse(y.id(), ky), y, ky);

        assertZero(codes);
    }

    @Test
    @DisplayName("REQ: reverse-in-full — an ordinary entry negating another is not its reversal (reverses_entry_id null), so the other can still be reversed in full")
    void ordinaryNegationIsNotAReversal() {
        Posted x = postTwoLines(500);
        Response negation = postOk(entryOf(x.codes(), x.negated()));
        assertThat(negation.jsonPath().getMap("")).as("an ordinary negation (body %s)", negation.asString())
                .containsEntry("reverses_entry_id", null);
        String key = newKey();

        assertReversalOf(reverse(x.id(), key), x, key);

        assertBalances(x.codes(), x.negated());
    }

    @Test
    @DisplayName("REQ: reverse-in-full — an entry that re-posts a reversed original is ordinary and can itself be reversed: X, reversal of X, X again, reversal of that")
    void repostedOriginalIsReversible() {
        Posted x = postTwoLines(800);
        reverseOk(x.id());
        Posted again = postEntry(x.codes(), x.amounts());
        String key = newKey();

        assertReversalOf(reverse(again.id(), key), again, key);

        assertZero(x.codes());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"reverses_entry_id", "reversesEntryId", "reverses_entry_id: null"})
    @DisplayName("REQ: reverse-in-full — the client never supplies a reversal: an ordinary post whose body names an entry to reverse (with that entry's negated lines) is 400 and creates nothing")
    void clientCannotPostAReversal(String variant) {
        Posted x = postTwoLines(700);
        String field = variant.startsWith("reverses_entry_id") ? "reverses_entry_id" : variant;
        Object value = variant.endsWith("null") ? null : x.id();

        Response r = post(newKey(), json(field, value, "lines", array(lines(x.codes(), x.negated()))));

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReversed(x);
    }

    // ------------------------------------------------------------------ reversal-restores-balances

    @Test
    @DisplayName("REQ: reversal-restores-balances — balances read before an entry is posted are exactly the balances after it is reversed (accounts with earlier activity)")
    void balancesReturnToBefore() {
        List<String> codes = newAccounts(3);
        postOk(entryOf(codes, cents(7000, -2500, -4500)));
        List<BigInteger> before = balancesOf(codes);
        Posted x = postEntry(codes, cents(1200, -500, -700));

        reverseOk(x.id());

        assertThat(balancesOf(codes)).as("balances after the reversal vs before the entry").isEqualTo(before);
    }

    @Test
    @DisplayName("REQ: reversal-restores-balances — a reversal retried with the same key is counted once: balances are exactly what they were before the original")
    void reversalCountedOnce() {
        List<String> codes = newAccounts(2);
        postOk(entryOf(codes, cents(9000, -9000)));
        List<BigInteger> before = balancesOf(codes);
        Posted x = postEntry(codes, cents(1100, -1100));
        String key = newKey();
        reverseOk(x.id(), key);

        assertReplayed(reverse(x.id(), key));

        assertThat(balancesOf(codes)).as("balances after reversal + retry vs before the entry").isEqualTo(before);
    }

    // ------------------------------------------------------------------ reversal-leaves-original

    @Test
    @DisplayName("REQ: reversal-leaves-original — after a reversal, retrying the original post (same key, same lines) replays it exactly as first returned, including reverses_entry_id null")
    void originalUnchangedAfterReversal() {
        Posted x = postTwoLines(640);
        reverseOk(x.id());

        Response retry = post(x.key(), entryOf(x.codes(), x.amounts()));

        assertReplayOf(retry, x.response());
        assertThat(retry.jsonPath().getMap("")).as("replayed original").containsEntry("reverses_entry_id", null);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-leaves-original — after a reversal and rejected attempts to reverse it again and to reverse the reversal, the original still replays exactly as first returned")
    void originalUnchangedAfterRejectedAttempts() {
        Posted x = postTwoLines(640);
        Response reversal = reverseOk(x.id());
        assertRule(reverse(x.id(), newKey()), ALREADY_REVERSED);
        assertRule(reverse(idOf(reversal), newKey()), CANNOT_REVERSE_REVERSAL);

        assertReplayOf(post(x.key(), entryOf(x.codes(), x.amounts())), x.response());
        assertZero(x.codes());
    }

    // ------------------------------------------------------------------ post-balanced-journal-entry (modified)

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — an ordinary post returns reverses_entry_id present and null, alongside entry_id, idempotency_key, created_at and lines")
    void ordinaryPostHasNullLink() {
        Posted x = postTwoLines(1500);

        Map<String, Object> body = x.response().jsonPath().getMap("");

        assertThat(body).as("posted entry (body %s)", x.response().asString())
                .containsKeys("entry_id", "idempotency_key", "created_at", "lines", "reverses_entry_id")
                .containsEntry("reverses_entry_id", null);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — the replay of an ordinary post also carries reverses_entry_id null")
    void ordinaryReplayHasNullLink() {
        Posted x = postTwoLines(1500);

        Response retry = post(x.key(), entryOf(x.codes(), x.amounts()));

        assertReplayOf(retry, x.response());
        assertThat(retry.jsonPath().getMap("")).as("replay (body %s)", retry.asString()).containsEntry("reverses_entry_id", null);
    }

    // ------------------------------------------------------------------ server-assigned-identity (modified)

    @Test
    @DisplayName("REQ: server-assigned-identity — an ordinary entry carries no identifier but entry_id and reverses_entry_id (null), at any level")
    void ordinaryEntryIdentifiers() {
        Posted x = postTwoLines(10);

        assertIdentifiersOnly(x.response(), "posted entry");
        assertThat(x.response().jsonPath().<Object>get("reverses_entry_id")).as("reverses_entry_id of an ordinary entry").isNull();
    }

    @Test
    @DisplayName("REQ: server-assigned-identity — a reversal carries no identifier but entry_id and reverses_entry_id, and reverses_entry_id is the original's public entry_id")
    void reversalIdentifiers() {
        Posted x = postTwoLines(10);

        Response r = reverseOk(x.id());

        assertIdentifiersOnly(r, "reversal");
        assertThat(r.jsonPath().getString("reverses_entry_id")).as("reverses_entry_id").isEqualTo(x.id());
    }

    @Test
    @DisplayName("REQ: server-assigned-identity — every entry_id the API returns (ordinary entry and reversal) is a canonical UUID, so it can be named in a reversal path")
    void returnedIdsAreCanonical() {
        Posted x = postTwoLines(10);
        Response r = reverseOk(x.id());

        assertThat(x.id()).as("ordinary entry_id").matches(CANONICAL);
        assertThat(idOf(r)).as("reversal entry_id").matches(CANONICAL);
    }

    // ------------------------------------------------------------------ reversal-has-no-body

    @Test
    @DisplayName("REQ: reversal-has-no-body — a reversal request whose body contains lines is 400 and creates nothing; the entry is not reversed")
    void bodyWithLinesRejected() {
        Posted x = postTwoLines(900);

        Response r = reverseWithBody(x.id(), newKey(), "application/json", entryOf(x.codes(), x.negated()));

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-has-no-body — the body {} is also a body: 400, creates nothing")
    void emptyObjectRejected() {
        Posted x = postTwoLines(900);

        Response r = reverseWithBody(x.id(), newKey(), "application/json", "{}");

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReversed(x);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"empty array", "JSON null", "empty JSON string", "number 0", "false", "lines empty", "lines null",
            "one line only", "unknown property memo", "reverses_entry_id", "reversesEntryId", "entry_id", "entryId",
            "idempotency_key", "the original's response", "malformed JSON", "not JSON at all"})
    @DisplayName("REQ: reversal-has-no-body — any non-empty body (JSON or not, snake_case or camelCase, known or unknown members) is 400 with the default type; nothing is created and the entry is not reversed")
    void anyBodyRejected(String kind) {
        Posted x = postTwoLines(900);
        String key = newKey();
        String body = switch (kind) {
            case "empty array" -> "[]";
            case "JSON null" -> "null";
            case "empty JSON string" -> "\"\"";
            case "number 0" -> "0";
            case "false" -> "false";
            case "lines empty" -> "{\"lines\":[]}";
            case "lines null" -> "{\"lines\":null}";
            case "one line only" -> entry(line(x.codes().get(0), -900));
            case "unknown property memo" -> json("memo", "typo in amount");
            case "reverses_entry_id", "entry_id" -> json(kind, x.id());
            case "reversesEntryId", "entryId" -> json(kind, x.id());
            case "idempotency_key" -> json("idempotency_key", key);
            case "the original's response" -> x.response().asString();
            case "malformed JSON" -> "{\"lines\":[" + line(x.codes().get(0), -900);
            case "not JSON at all" -> "reverse it please";
            default -> throw new IllegalArgumentException(kind);
        };

        Response r = reverseWithBody(x.id(), key, "application/json", body);

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertNotReversed(x);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"application/json; charset=UTF-8", "text/plain", "application/xml", "application/x-www-form-urlencoded",
            "application/octet-stream"})
    @DisplayName("REQ: reversal-has-no-body — a body is 400 whatever its Content-Type (the spec answers any non-empty body with 400); nothing created")
    void bodyRejectedWhateverContentType(String contentType) {
        Posted x = postTwoLines(900);

        RawResponse r = rawPostTo(reversalPath(x.id()), contentType, keyHeader(latin1(newKey())), "{}");

        observe("reversal with body {} as " + contentType, r.status(), r.contentType(), r.body());
        assertRawProblem(r, 400);
        assertThat(JsonPath.from(r.body()).getString("type")).as("a 400 keeps the default type").isIn(null, "about:blank");
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-has-no-body — a body sent with no Content-Type header is 400; nothing created")
    void bodyWithoutContentTypeRejected() {
        Posted x = postTwoLines(900);

        RawResponse r = rawPostTo(reversalPath(x.id()), null, keyHeader(latin1(newKey())), "{}");

        observe("reversal with body {} and no Content-Type", r.status(), r.contentType(), r.body());
        assertRawProblem(r, 400);
        assertNotReversed(x);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"no body, no Content-Length, no Content-Type", "no body, no Content-Length, application/json",
            "Content-Length 0, no Content-Type", "Content-Length 0, application/json", "Content-Length 0, text/plain",
            "chunked with no data"})
    @DisplayName("REQ: reversal-has-no-body — a request with no body, or an empty body, is accepted (201 reversal) however the absence is spelled on the wire")
    void emptyBodyAccepted(String kind) {
        Posted x = postTwoLines(410);
        String key = newKey();
        String path = reversalPath(x.id());
        byte[] keyLine = keyHeader(latin1(key));
        RawResponse r = switch (kind) {
            case "no body, no Content-Length, no Content-Type" -> rawPostTo(path, null, keyLine, null);
            case "no body, no Content-Length, application/json" -> rawPostTo(path, "application/json", keyLine, null);
            case "Content-Length 0, no Content-Type" -> rawPostTo(path, null, keyLine, "");
            case "Content-Length 0, application/json" -> rawPostTo(path, "application/json", keyLine, "");
            case "Content-Length 0, text/plain" -> rawPostTo(path, "text/plain", keyLine, "");
            case "chunked with no data" -> rawExchange(concat(latin1("POST /api/v1" + path + " HTTP/1.1\r\n"
                    + "Host: localhost:" + RestAssured.port + "\r\nAccept: */*\r\nConnection: close\r\n"
                    + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n"), keyLine, latin1("\r\n0\r\n\r\n")));
            default -> throw new IllegalArgumentException(kind);
        };

        observe("reversal, " + kind, r.status(), r.contentType(), r.body());
        assertRawReversalOf(r, x, key);
        assertZero(x.codes());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"one space", "newline", "spaces, CR LF and tab"})
    @DisplayName("REQ: reversal-has-no-body — a whitespace-only body (spec silent: 'non-empty' vs 'empty') is either a 201 reversal or a 400 that creates nothing; never 500")
    void whitespaceOnlyBody(String kind) {
        Posted x = postTwoLines(410);
        String key = newKey();
        String body = switch (kind) {
            case "one space" -> " ";
            case "newline" -> "\n";
            case "spaces, CR LF and tab" -> "  \r\n\t ";
            default -> throw new IllegalArgumentException(kind);
        };

        Response r = reverseWithBody(x.id(), key, "application/json", body);

        observe("reversal with whitespace-only body (" + kind + ")", r);
        assertThat(r.statusCode()).as("status (body %s)", r.asString()).isIn(201, 400);
        if (r.statusCode() == 201) {
            assertReversalOf(r, x, key);
        } else {
            assertProblem(r, 400);
            assertNotReversed(x);
        }
    }

    // ------------------------------------------------------------------ reverse-at-most-once

    @Test
    @DisplayName("REQ: reverse-at-most-once — reversing an already reversed entry with a new key is 422 entry-already-reversed (never 409), the detail names the original's entry_id, nothing created")
    void secondReversalRejected() {
        Posted x = postTwoLines(1250);
        reverseOk(x.id());

        Response r = reverse(x.id(), newKey());

        assertThat(r.statusCode()).as("never 409 (body %s)", r.asString()).isNotEqualTo(409);
        String detail = assertRule(r, ALREADY_REVERSED);
        assertNotReplayed(r);
        assertThat(detail).as("detail names the entry that was to be reversed").containsIgnoringCase(x.id());
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reverse-at-most-once — a rejected second reversal leaves the balances as they were before the rejected request (accounts with other activity)")
    void rejectedSecondReversalLeavesBalances() {
        List<String> codes = newAccounts(2);
        postOk(entryOf(codes, cents(3000, -3000)));
        Posted x = postEntry(codes, cents(1250, -1250));
        reverseOk(x.id());
        List<BigInteger> before = balancesOf(codes);

        assertRule(reverse(x.id(), newKey()), ALREADY_REVERSED);

        assertThat(balancesOf(codes)).as("balances after the rejected second reversal").isEqualTo(before);
    }

    @Test
    @DisplayName("REQ: reverse-at-most-once — the existing reversal is unchanged by a rejected second one: retrying it with its key still replays it exactly as first returned")
    void existingReversalUnchanged() {
        Posted x = postTwoLines(1250);
        String key = newKey();
        Response first = reverseOk(x.id(), key);
        assertRule(reverse(x.id(), newKey()), ALREADY_REVERSED);

        assertReplayOf(reverse(x.id(), key), first);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reverse-at-most-once — three further attempts under three new keys are each 422 entry-already-reversed; still one reversal")
    void repeatedAttemptsAllRejected() {
        Posted x = postTwoLines(1250);
        reverseOk(x.id());

        for (int i = 0; i < 3; i++) assertRule(reverse(x.id(), newKey()), ALREADY_REVERSED);

        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reverse-at-most-once — naming the reversed entry in the other letter case is still the same entry: 422 entry-already-reversed")
    void secondReversalOtherCaseRejected() {
        Posted x = postTwoLines(1250);
        reverseOk(x.id());

        Response r = reverse(otherCase(x.id()), newKey());

        String detail = assertRule(r, ALREADY_REVERSED);
        assertThat(detail).as("detail names the entry").containsIgnoringCase(x.id());
        assertZero(x.codes());
    }

    // ------------------------------------------------------------------ no-reversal-of-a-reversal

    @Test
    @DisplayName("REQ: no-reversal-of-a-reversal — reversing a reversal is 422 cannot-reverse-a-reversal; the detail names that entry_id and says to post the original movement again; nothing created")
    void reversalOfReversalRejected() {
        Posted x = postTwoLines(2200);
        String reversal = idOf(reverseOk(x.id()));

        Response r = reverse(reversal, newKey());

        observe("reversal of a reversal", r);
        String detail = assertRule(r, CANNOT_REVERSE_REVERSAL);
        assertNotReplayed(r);
        assertThat(detail).as("detail names the reversal's entry_id").containsIgnoringCase(reversal);
        assertThat(detail.toLowerCase(Locale.ROOT))
                .as("detail says a reversal is undone by posting the original movement again")
                .contains("post");
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: no-reversal-of-a-reversal — a reversal is undone by posting the original's lines again as an ordinary entry with a new key: 201, reverses_entry_id null, balances as just after the original")
    void undoReversalByPostingAgain() {
        Posted x = postTwoLines(2200);
        reverseOk(x.id());

        Response again = postOk(entryOf(x.codes(), x.amounts()));

        assertThat(again.jsonPath().getMap("")).as("re-posted original").containsEntry("reverses_entry_id", null);
        assertNotReplayed(again);
        assertBalances(x.codes(), x.amounts());
    }

    @Test
    @DisplayName("REQ: no-reversal-of-a-reversal — naming the reversal in the other letter case is still 422 cannot-reverse-a-reversal")
    void reversalOfReversalOtherCaseRejected() {
        Posted x = postTwoLines(2200);
        String reversal = idOf(reverseOk(x.id()));

        assertRule(reverse(otherCase(reversal), newKey()), CANNOT_REVERSE_REVERSAL);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: no-reversal-of-a-reversal — three attempts to reverse a reversal are each rejected, and the reversal itself still replays under its own key")
    void reversalOfReversalStaysRejected() {
        Posted x = postTwoLines(2200);
        String key = newKey();
        Response reversal = reverseOk(x.id(), key);

        for (int i = 0; i < 3; i++) assertRule(reverse(idOf(reversal), newKey()), CANNOT_REVERSE_REVERSAL);

        assertReplayOf(reverse(x.id(), key), reversal);
        assertZero(x.codes());
    }

    // ------------------------------------------------------------------ entry-to-reverse-exists

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"random", "random, upper-case", "nil", "all f", "version-1 layout"})
    @DisplayName("REQ: entry-to-reverse-exists — a well-formed entry_id that matches no entry is 404 application/problem+json (default type); retried, it is 404 again, not a replay")
    void unknownEntryIs404(String kind) {
        String id = switch (kind) {
            case "random" -> UUID.randomUUID().toString();
            case "random, upper-case" -> UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            case "nil" -> "00000000-0000-0000-0000-000000000000";
            case "all f" -> "ffffffff-ffff-ffff-ffff-ffffffffffff";
            case "version-1 layout" -> "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
            default -> throw new IllegalArgumentException(kind);
        };
        String key = newKey();

        Response r = reverse(id, key);
        Response again = reverse(id, key);

        observe("reversal of unknown " + kind, r);
        assertProblem(r, 404);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertProblem(again, 404);
        assertNotReplayed(again);
    }

    @Test
    @DisplayName("REQ: entry-to-reverse-exists — the path value not-a-uuid is 400 (default type), not 404")
    void notAUuidIs400() {
        Response r = reverseAtSegment("not-a-uuid", newKey());

        assertProblem(r, 400);
        assertDefaultType(r);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"hyphens removed", "1-1-1-1-1", "leading space", "trailing space", "wrapped in braces",
            "urn:uuid: prefix", "last digit dropped (35 chars)", "extra digit appended (37 chars)",
            "extra leading zero in the first group", "leading plus sign", "first hyphen moved (9-3-4-4-12)",
            "non-hex letter g", "Arabic-Indic digit", "fullwidth digit", "an account code", "null"})
    @DisplayName("REQ: entry-to-reverse-exists — a non-canonical spelling of an existing entry's id is 400 (default type), never reshaped into a UUID: the entry is not reversed")
    void nonCanonicalIdIs400(String kind) {
        Posted x = postTwoLines(330);
        String id = x.id();
        String segment = switch (kind) {
            case "hyphens removed" -> id.replace("-", "");
            case "1-1-1-1-1" -> "1-1-1-1-1";
            case "leading space" -> "%20" + id;
            case "trailing space" -> id + "%20";
            case "wrapped in braces" -> "%7B" + id + "%7D";
            case "urn:uuid: prefix" -> "urn%3Auuid%3A" + id;
            case "last digit dropped (35 chars)" -> id.substring(0, 35);
            case "extra digit appended (37 chars)" -> id + "0";
            case "extra leading zero in the first group" -> "0" + id;
            case "leading plus sign" -> "%2B" + id;
            case "first hyphen moved (9-3-4-4-12)" -> id.substring(0, 8) + id.charAt(9) + "-" + id.substring(10);
            case "non-hex letter g" -> id.substring(0, 35) + "g";
            case "Arabic-Indic digit" -> pathSegment(withUnicodeDigit(id, '٠'));
            case "fullwidth digit" -> pathSegment(withUnicodeDigit(id, '０'));
            case "an account code" -> pathSegment(uniqueCode());
            case "null" -> "null";
            default -> throw new IllegalArgumentException(kind);
        };

        Response r = reverseAtSegment(segment, newKey());

        observe("reversal at /" + segment, r);
        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: entry-to-reverse-exists — an existing entry named with its hex digits in the other letter case is reversed (201), and reverses_entry_id is the original's entry_id as first returned")
    void otherLetterCaseAccepted() {
        Posted x = postTwoLines(450);
        String key = newKey();

        Response r = reverse(otherCase(x.id()), key);

        assertReversalOf(r, x, key);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: entry-to-reverse-exists — an existing entry named in mixed letter case (spec: 'either letter case') is a 201 reversal of it or a 400; never 404, never 500")
    void mixedLetterCase() {
        Posted x = postTwoLines(450);
        String key = newKey();

        Response r = reverse(mixedCase(x.id()), key);

        observe("reversal named in mixed case " + mixedCase(x.id()), r);
        assertThat(r.statusCode()).as("status (body %s)", r.asString()).isIn(201, 400);
        if (r.statusCode() == 201) {
            assertReversalOf(r, x, key);
        } else {
            assertProblem(r, 400);
            assertNotReversed(x);
        }
    }

    @Test
    @DisplayName("REQ: entry-to-reverse-exists — an empty entry_id segment (/journal-entries//reversal; spec silent) is a 400/404/405 problem+json; never 201 or 500")
    void emptyIdSegment() {
        Response r = reverseAtSegment("", newKey());

        observe("reversal at /journal-entries//reversal", r);
        assertProblemIn(r, 400, 404, 405);
    }

    // ------------------------------------------------------------------ reversal-key-required

    @Test
    @DisplayName("REQ: reversal-key-required — a reversal without an Idempotency-Key header is 400 (default type); nothing created, the entry is not reversed")
    void missingKeyRejected() {
        Posted x = postTwoLines(560);

        Response r = given().when().post(REVERSAL, x.id());

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReversed(x);
    }

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("REQ: reversal-key-required — an empty or whitespace-only Idempotency-Key is 400; nothing created")
    void emptyKeyRejected(String value) {
        Posted x = postTwoLines(560);

        RawResponse r = rawPostTo(reversalPath(x.id()), null, keyHeader(latin1(value)), null);

        assertRawProblem(r, 400);
        assertNotReversed(x);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {256, 257, 1000})
    @DisplayName("REQ: reversal-key-required — an Idempotency-Key longer than 255 characters is 400; nothing created")
    void tooLongKeyRejected(int length) {
        Posted x = postTwoLines(560);

        Response r = reverse(x.id(), padKey(length));

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-key-required — a 255-character Idempotency-Key (the longest allowed) reverses the entry, is echoed verbatim, and a retry with it replays")
    void maxLengthKeyAccepted() {
        Posted x = postTwoLines(560);
        String key = padKey(255);

        Response r = reverse(x.id(), key);

        assertReversalOf(r, x, key);
        assertReplayOf(reverse(x.id(), key), r);
    }

    @Test
    @DisplayName("REQ: reversal-key-required — a 256-character key extending the 255-character key of a reversal is 400: never truncated into a replay or an entry-already-reversed")
    void overLongKeyNotTruncated() {
        Posted x = postTwoLines(560);
        String key = padKey(255);
        reverseOk(x.id(), key);

        Response r = reverse(x.id(), key + "k");

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-key-required — a single-character Idempotency-Key is accepted: a free one reverses the entry (201, echoed); one owned by an earlier test is skipped only on 422 idempotency-key-reused")
    void singleCharKeyAccepted() {
        Posted x = postTwoLines(560);
        for (char c = 0x21; c <= 0x7e; c++) {
            RawResponse r = rawPostTo(reversalPath(x.id()), null, keyHeader(new byte[] {(byte) c}), null);
            if (r.status() == 422 && KEY_REUSED.equals(JsonPath.from(r.body()).getString("type"))) continue;
            assertRawReversalOf(r, x, String.valueOf(c));
            return;
        }
        fail("all 94 single-character keys are already owned by entries in this database; run against a fresh one");
    }

    @Test
    @DisplayName("REQ: reversal-key-required — a key made of every visible ASCII character (0x21..0x7E) is accepted and echoed verbatim")
    void allVisibleAsciiKeyAccepted() {
        Posted x = postTwoLines(560);
        String key = uniqueTail() + allVisibleAscii();

        RawResponse r = rawPostTo(reversalPath(x.id()), null, keyHeader(latin1(key)), null);

        assertRawReversalOf(r, x, key);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"interior space", "interior tab", "UTF-8 e-acute", "Latin-1 e-acute byte"})
    @DisplayName("REQ: reversal-key-required — an Idempotency-Key with a character other than visible ASCII that the application receives is 400 problem+json; nothing created")
    void keyWithInvalidCharacterRejected(String kind) {
        byte[] bad = switch (kind) {
            case "interior space" -> new byte[] {' '};
            case "interior tab" -> new byte[] {'\t'};
            case "UTF-8 e-acute" -> "é".getBytes(StandardCharsets.UTF_8);
            case "Latin-1 e-acute byte" -> new byte[] {(byte) 0xe9};
            default -> throw new IllegalArgumentException(kind);
        };
        Posted x = postTwoLines(560);

        RawResponse r = rawPostTo(reversalPath(x.id()), null,
                keyHeader(concat(latin1("key-"), bad, latin1(uniqueTail()))), null);

        observe("reversal key with " + kind, r.status(), r.contentType(), r.body());
        assertRawProblem(r, 400);
        assertNotReversed(x);
    }

    /**
     * The carve-out in "Reversal errors are reported as problem+json": the HTTP server rejects a control
     * character in a header before the application is involved, so the 400 is the server's own error page.
     * The status and "not reversed" are the contract; the body format is deliberately not asserted.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"DEL 0x7F", "control 0x01"})
    @DisplayName("REQ: reversal-key-required — an Idempotency-Key with a control character is rejected by the HTTP server before the application: 400 (the server's own page, not problem+json); not reversed")
    void keyWithControlCharacterRejectedByServer(String kind) {
        byte[] bad = switch (kind) {
            case "DEL 0x7F" -> new byte[] {0x7f};
            case "control 0x01" -> new byte[] {0x01};
            default -> throw new IllegalArgumentException(kind);
        };
        Posted x = postTwoLines(560);

        RawResponse r = rawPostTo(reversalPath(x.id()), null,
                keyHeader(concat(latin1("key-"), bad, latin1(uniqueTail()))), null);

        observe("reversal key with " + kind, r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (Content-Type %s, body %s)", r.contentType(), r.body()).isEqualTo(400);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-key-required — two Idempotency-Key headers with different values are 400; nothing created")
    void twoDifferentKeyHeadersRejected() {
        Posted x = postTwoLines(560);

        RawResponse r = rawPostTo(reversalPath(x.id()), null,
                concat(keyHeader(latin1(newKey())), keyHeader(latin1(newKey()))), null);

        observe("reversal with two different Idempotency-Key headers", r.status(), r.contentType(), r.body());
        assertRawProblem(r, 400);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-key-required — two Idempotency-Key headers with the same value are still more than one header: 400; nothing created")
    void twoIdenticalKeyHeadersRejected() {
        Posted x = postTwoLines(560);
        String key = newKey();

        RawResponse r = rawPostTo(reversalPath(x.id()), null, concat(keyHeader(latin1(key)), keyHeader(latin1(key))), null);

        observe("reversal with the same Idempotency-Key header twice", r.status(), r.contentType(), r.body());
        assertRawProblem(r, 400);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-key-required — one Idempotency-Key header whose value contains a comma is one well-formed key (visible ASCII): 201, echoed verbatim, not split")
    void commaInOneKeyHeaderAccepted() {
        Posted x = postTwoLines(560);
        String key = uniqueTail() + "," + uniqueTail();

        RawResponse r = rawPostTo(reversalPath(x.id()), null, keyHeader(latin1(key)), null);

        observe("reversal with one Idempotency-Key header holding a comma", r.status(), r.contentType(), r.body());
        assertRawReversalOf(r, x, key);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"idempotency-key", "IDEMPOTENCY-KEY", "idempotency-KEY"})
    @DisplayName("REQ: reversal-key-required — the Idempotency-Key header name is matched case-insensitively (HTTP semantics)")
    void keyHeaderNameCaseInsensitive(String headerName) {
        Posted x = postTwoLines(560);
        String key = newKey();

        Response r = given().header(headerName, key).when().post(REVERSAL, x.id());

        assertReversalOf(r, x, key);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"header Idempotency_Key", "header IdempotencyKey", "header X-Idempotency-Key", "header Idempotency-Keys",
            "query parameter idempotency_key", "query parameter Idempotency-Key"})
    @DisplayName("REQ: reversal-key-required — a key sent anywhere but the Idempotency-Key header (other header names, query string) leaves the header missing: 400, nothing created")
    void keyInWrongPlaceRejected(String where) {
        Posted x = postTwoLines(560);
        String name = where.substring(where.lastIndexOf(' ') + 1);

        Response r = where.startsWith("query")
                ? given().queryParam(name, newKey()).when().post(REVERSAL, x.id())
                : given().header(name, newKey()).when().post(REVERSAL, x.id());

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReversed(x);
    }

    // ------------------------------------------------------------------ reversal-retry-replayed

    @Test
    @DisplayName("REQ: reversal-retry-replayed — an identical retry is 201 with the identical body (entry_id, created_at, reverses_entry_id) and Idempotent-Replayed: true; only the retry carries the header")
    void identicalRetryReplays() {
        Posted x = postTwoLines(1700);
        String key = newKey();
        Response first = reverseOk(x.id(), key);

        Response retry = reverse(x.id(), key);

        assertNotReplayed(first);
        assertReplayOf(retry, first);
        assertThat(retry.jsonPath().getString("reverses_entry_id")).as("replayed reverses_entry_id").isEqualTo(x.id());
    }

    @Test
    @DisplayName("REQ: reversal-retry-replayed — retrying a successful reversal with its key is never reported as entry-already-reversed: 201 with Idempotent-Replayed: true, not 422")
    void retryNeverAlreadyReversed() {
        Posted x = postTwoLines(1700);
        String key = newKey();
        reverseOk(x.id(), key);

        Response retry = reverse(x.id(), key);

        assertThat(typeOf(retry)).as("a retry is not entry-already-reversed (body %s)", retry.asString()).isNotEqualTo(ALREADY_REVERSED);
        assertReplayed(retry);
    }

    @Test
    @DisplayName("REQ: reversal-retry-replayed — a first reversal with a never-used key is 201 with no Idempotent-Replayed header at all")
    void firstReversalHasNoReplayHeader() {
        Posted x = postTwoLines(1700);

        assertNotReplayed(reverseOk(x.id()));
    }

    @Test
    @DisplayName("REQ: reversal-retry-replayed — five successive retries each replay the first body; the balances count the reversal once")
    void repeatedRetriesReplay() {
        Posted x = postTwoLines(1700);
        String key = newKey();
        Response first = reverseOk(x.id(), key);

        for (int i = 0; i < 5; i++) assertReplayOf(reverse(x.id(), key), first);

        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-retry-replayed — a retry spelling 'no body' differently (Content-Length 0 with application/json) is the same request and is replayed")
    void retryWithOtherEmptyBodySpellingReplays() {
        Posted x = postTwoLines(1700);
        String key = newKey();
        Response first = reverseOk(x.id(), key);

        RawResponse retry = rawPostTo(reversalPath(x.id()), "application/json", keyHeader(latin1(key)), "");

        assertThat(retry.status()).as("retry (body %s)", retry.body()).isEqualTo(201);
        assertThat(retry.headers().get(REPLAYED.toLowerCase(Locale.ROOT))).as("retry's %s header", REPLAYED).isEqualTo("true");
        assertThat(JsonPath.from(retry.body()).getMap("")).as("replayed body").isEqualTo(first.jsonPath().getMap(""));
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-retry-replayed — a retry naming the same entry in the other letter case is a retry for that same entry and is replayed")
    void retryInOtherLetterCaseReplays() {
        Posted x = postTwoLines(1700);
        String key = newKey();
        Response first = reverseOk(x.id(), key);

        assertReplayOf(reverse(otherCase(x.id()), key), first);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-retry-replayed — a retry after the original's own replay, ten unrelated posts and a pause is still replayed")
    void laterRetryReplays() throws InterruptedException {
        Posted x = postTwoLines(1700);
        String key = newKey();
        Response first = reverseOk(x.id(), key);
        assertReplayed(post(x.key(), entryOf(x.codes(), x.amounts())));
        String c = newAccount("ASSET");
        String d = newAccount("LIABILITY");
        for (int i = 1; i <= 10; i++) postOk(entry(line(c, i), line(d, -i)));
        Thread.sleep(1_000);

        assertReplayOf(reverse(x.id(), key), first);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-retry-replayed — a replayed reversal is application/json, snake_case, and has the same members as the first response")
    void replayShape() {
        Posted x = postTwoLines(1700);
        String key = newKey();
        Response first = reverseOk(x.id(), key);

        Response retry = reverse(x.id(), key);

        assertReplayed(retry);
        assertThat(retry.contentType()).as("Content-Type of a replay").startsWith("application/json");
        assertSnakeCaseKeys(retry.jsonPath().getMap(""), "replayed reversal");
        assertThat(retry.jsonPath().getMap("").keySet()).as("members").isEqualTo(first.jsonPath().getMap("").keySet());
    }

    // ------------------------------------------------------------------ reversal-key-reuse-rejected

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — the key of an ordinary entry, used to reverse a different existing entry, is 422 idempotency-key-reused; nothing created")
    void ordinaryKeyRejectedForReversal() {
        Posted other = postTwoLines(100);
        Posted x = postTwoLines(600);

        Response r = reverse(x.id(), other.key());

        assertReused(r);
        assertNotReversed(x);
        assertBalances(other.codes(), other.amounts());
    }

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — the key of entry A's reversal, used to reverse entry B, is 422 idempotency-key-reused; B is not reversed")
    void otherReversalKeyRejected() {
        Posted a = postTwoLines(100);
        Posted b = postTwoLines(600);
        String key = newKey();
        reverseOk(a.id(), key);

        Response r = reverse(b.id(), key);

        assertReused(r);
        assertNotReversed(b);
        assertZero(a.codes());
    }

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — a reversal's key cannot post an ordinary entry, even with lines identical to the reversal's: 422 idempotency-key-reused, nothing created")
    void reversalKeyCannotPostIdenticalLines() {
        Posted x = postTwoLines(600);
        String key = newKey();
        reverseOk(x.id(), key);

        Response r = post(key, entryOf(x.codes(), x.negated()));

        assertReused(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — a reversal's key cannot post an ordinary entry with the original's lines either: 422 idempotency-key-reused, nothing created")
    void reversalKeyCannotPostOriginalLines() {
        Posted x = postTwoLines(600);
        String key = newKey();
        reverseOk(x.id(), key);

        Response r = post(key, entryOf(x.codes(), x.amounts()));

        assertReused(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — an ordinary entry carrying exactly the negation of X under key K, then the reversal of X with K: 422 idempotency-key-reused (identical lines, but one is a reversal)")
    void identicalLinesButOneIsAReversal() {
        Posted x = postTwoLines(600);
        String key = newKey();
        postOk(key, entryOf(x.codes(), x.negated()));

        Response r = reverse(x.id(), key);

        assertReused(r);
        assertZero(x.codes());
        String unused = newKey();
        assertReversalOf(reverse(x.id(), unused), x, unused);
        assertBalances(x.codes(), x.negated());
    }

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — reversing an entry with that entry's own posting key is 422 idempotency-key-reused (the key belongs to a different request); the entry is not reversed")
    void originalsOwnKeyRejected() {
        Posted x = postTwoLines(600);

        Response r = reverse(x.id(), x.key());

        assertReused(r);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — the rejection of a reversal under an ordinary entry's key names the key and reveals neither that entry's account codes nor its amounts")
    void detailNamesKeyNotStoredLines() {
        Posted stored = postEntry(List.of(newAccount("ASSET"), newAccount("LIABILITY")), cents(STORED, -STORED));
        Posted x = postTwoLines(600);

        Response r = reverse(x.id(), stored.key());

        String detail = assertReused(r);
        assertThat(detail).as("detail must name the key").contains(stored.key());
        assertRevealsNothingOf(r.asString(), "problem body", stored.codes());
    }

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — an ordinary post under a reversal's key is rejected naming the key, revealing neither the reversal's account codes nor its amounts")
    void detailHidesStoredReversal() {
        Posted x = postEntry(List.of(newAccount("ASSET"), newAccount("LIABILITY")), cents(STORED, -STORED));
        String key = newKey();
        reverseOk(x.id(), key);
        String c = newAccount("REVENUE");
        String d = newAccount("EXPENSE");

        Response r = post(key, entry(line(c, 5150), line(d, -5150)));

        String detail = assertReused(r);
        assertThat(detail).as("detail must name the key").contains(key);
        assertRevealsNothingOf(r.asString(), "problem body", x.codes());
        assertUntouched(c, d);
    }

    @Test
    @DisplayName("REQ: reversal-key-reuse-rejected — a rejected reuse leaves the key bound to its reversal: retrying the reversal still replays it, and the reuse is rejected again")
    void rejectedReuseLeavesKeyBound() {
        Posted a = postTwoLines(100);
        Posted b = postTwoLines(600);
        String key = newKey();
        Response reversal = reverseOk(a.id(), key);

        assertReused(reverse(b.id(), key));
        assertReplayOf(reverse(a.id(), key), reversal);
        assertReused(reverse(b.id(), key));

        assertZero(a.codes());
        assertBalances(b.codes(), b.amounts());
    }

    // ------------------------------------------------------------------ reversal-check-order

    @Test
    @DisplayName("REQ: reversal-check-order — a malformed Idempotency-Key on an unknown entry is 400, not 404")
    void malformedKeyBeforeMissingEntry() {
        Response r = reverse(UUID.randomUUID().toString(), padKey(256));

        assertProblem(r, 400);
        assertDefaultType(r);
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a key already used by an existing entry, on an unknown entry, is 404, not 422")
    void usedKeyOnUnknownEntryIs404() {
        Posted other = postTwoLines(100);

        Response r = reverse(UUID.randomUUID().toString(), other.key());

        assertProblem(r, 404);
        assertDefaultType(r);
        assertNotReplayed(r);
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a key already used by an ordinary entry, on an existing not-yet-reversed entry, is 422 idempotency-key-reused")
    void usedKeyOnExistingEntryIsKeyReused() {
        Posted other = postTwoLines(100);
        Posted x = postTwoLines(600);

        assertReused(reverse(x.id(), other.key()));
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-check-order — reversing a reversal with the very key that reversal was created with is 422 cannot-reverse-a-reversal, not a replay")
    void reversalOfReversalWithItsOwnKey() {
        Posted x = postTwoLines(600);
        String key = newKey();
        Response reversal = reverseOk(x.id(), key);

        Response r = reverse(idOf(reversal), key);

        assertRule(r, CANNOT_REVERSE_REVERSAL);
        assertNotReplayed(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-check-order — reversing a reversal with a key used by an unrelated ordinary entry is 422 cannot-reverse-a-reversal (third check), not idempotency-key-reused (fourth)")
    void cannotReverseBeforeKeyReuse() {
        Posted other = postTwoLines(100);
        Posted x = postTwoLines(600);
        String reversal = idOf(reverseOk(x.id()));

        assertRule(reverse(reversal, other.key()), CANNOT_REVERSE_REVERSAL);
        assertZero(x.codes());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"an unrelated ordinary entry's key", "another entry's reversal key", "the entry's own posting key"})
    @DisplayName("REQ: reversal-check-order — reversing an already reversed entry under a key used by a different request is 422 idempotency-key-reused, not entry-already-reversed")
    void keyReuseBeforeAlreadyReversed(String kind) {
        Posted x = postTwoLines(600);
        reverseOk(x.id());
        String key = switch (kind) {
            case "an unrelated ordinary entry's key" -> postTwoLines(100).key();
            case "another entry's reversal key" -> reverseOk(postTwoLines(100).id()).jsonPath().getString("idempotency_key");
            case "the entry's own posting key" -> x.key();
            default -> throw new IllegalArgumentException(kind);
        };

        assertReused(reverse(x.id(), key));
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a body on a reversal of an unknown entry is 400 (first check), not 404")
    void bodyBeforeMissingEntry() {
        Response r = reverseWithBody(UUID.randomUUID().toString(), newKey(), "application/json", "{}");

        assertProblem(r, 400);
        assertDefaultType(r);
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a body on a reversal of a reversal is 400, not cannot-reverse-a-reversal")
    void bodyBeforeCannotReverse() {
        Posted x = postTwoLines(600);
        String reversal = idOf(reverseOk(x.id()));

        Response r = reverseWithBody(reversal, newKey(), "application/json", "{}");

        assertProblem(r, 400);
        assertDefaultType(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a body on a second reversal of an entry is 400, not entry-already-reversed")
    void bodyBeforeAlreadyReversed() {
        Posted x = postTwoLines(600);
        reverseOk(x.id());

        Response r = reverseWithBody(x.id(), newKey(), "application/json", "{}");

        assertProblem(r, 400);
        assertDefaultType(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a retry of a successful reversal that now carries a body is 400, never a replay")
    void bodyOnRetryIsNotReplayed() {
        Posted x = postTwoLines(600);
        String key = newKey();
        reverseOk(x.id(), key);

        Response r = reverseWithBody(x.id(), key, "application/json", "{}");

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a retry of a successful reversal with the entry_id spelled without hyphens is 400, never a replay")
    void malformedIdOnRetryIsNotReplayed() {
        Posted x = postTwoLines(600);
        String key = newKey();
        reverseOk(x.id(), key);

        Response r = reverseAtSegment(x.id().replace("-", ""), key);

        assertProblem(r, 400);
        assertNotReplayed(r);
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: reversal-check-order — a reversal of a reversal without an Idempotency-Key is 400, not cannot-reverse-a-reversal")
    void missingKeyBeforeCannotReverse() {
        Posted x = postTwoLines(600);
        String reversal = idOf(reverseOk(x.id()));

        Response r = given().when().post(REVERSAL, reversal);

        assertProblem(r, 400);
        assertDefaultType(r);
    }

    @Test
    @DisplayName("REQ: reversal-check-order — an unknown entry under the key of an existing reversal is 404, not a replay of that reversal and not idempotency-key-reused")
    void unknownEntryWithReversalKeyIs404() {
        Posted x = postTwoLines(600);
        String key = newKey();
        reverseOk(x.id(), key);

        Response r = reverse(UUID.randomUUID().toString(), key);

        assertProblem(r, 404);
        assertDefaultType(r);
        assertNotReplayed(r);
    }

    // ------------------------------------------------------------------ concurrent-reversals-one

    @Test
    @DisplayName("REQ: concurrent-reversals-one — two simultaneous reversals of one entry under different keys (5 rounds): one 201, the other 422 entry-already-reversed; one reversal exists")
    void twoSimultaneousDifferentKeys() throws Exception {
        for (int round = 0; round < 5; round++) {
            Posted x = postTwoLines(100);

            List<Response> rs = simultaneously(List.of(() -> reverse(x.id(), newKey()), () -> reverse(x.id(), newKey())));

            assertOneCreatedRestAlreadyReversed(rs, x, "round " + round);
            assertZero(x.codes());
        }
    }

    @Test
    @DisplayName("REQ: concurrent-reversals-one — eight simultaneous reversals of one entry under eight keys: one 201, seven 422 entry-already-reversed; one reversal exists")
    void manySimultaneousDifferentKeys() throws Exception {
        Posted x = postTwoLines(100);
        List<Callable<Response>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) requests.add(() -> reverse(x.id(), newKey()));

        assertOneCreatedRestAlreadyReversed(simultaneously(requests), x, "8 keys");
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: concurrent-reversals-one — two simultaneous reversals of one entry under the same key (5 rounds): both 201 with identical bodies, exactly one Idempotent-Replayed: true; one reversal exists")
    void twoSimultaneousSameKey() throws Exception {
        for (int round = 0; round < 5; round++) {
            Posted x = postTwoLines(100);
            String key = newKey();

            List<Response> rs = simultaneously(List.of(() -> reverse(x.id(), key), () -> reverse(x.id(), key)));

            assertOneOriginalRestReplays(rs, x, key, "round " + round);
            assertZero(x.codes());
        }
    }

    @Test
    @DisplayName("REQ: concurrent-reversals-one — eight simultaneous reversals under one key: one original 201, seven replays of it; one reversal exists")
    void manySimultaneousSameKey() throws Exception {
        Posted x = postTwoLines(100);
        String key = newKey();
        List<Callable<Response>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) requests.add(() -> reverse(x.id(), key));

        assertOneOriginalRestReplays(simultaneously(requests), x, key, "8 same key");
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: concurrent-reversals-one — four reversals under key K1 and four under K2, simultaneously: one original 201, the winner's key replays it, the other key gets 422 entry-already-reversed")
    void simultaneousMixedKeys() throws Exception {
        Posted x = postTwoLines(100);
        String k1 = newKey();
        String k2 = newKey();
        List<String> keys = List.of(k1, k2, k1, k2, k1, k2, k1, k2);
        List<Callable<Response>> requests = new ArrayList<>();
        for (String k : keys) requests.add(() -> reverse(x.id(), k));

        List<Response> rs = simultaneously(requests);

        Response original = null;
        for (Response r : rs) {
            if (r.statusCode() == 201 && r.headers().getValues(REPLAYED).isEmpty()) {
                assertThat(original).as("only one response may be the original 201 (second: %s)", r.asString()).isNull();
                original = r;
            }
        }
        assertThat(original).as("exactly one response must be the original 201 without %s", REPLAYED).isNotNull();
        String winner = original.jsonPath().getString("idempotency_key");
        assertReversalOf(original, x, winner);
        for (int i = 0; i < rs.size(); i++) {
            Response r = rs.get(i);
            if (r == original) continue;
            if (keys.get(i).equals(winner)) assertReplayOf(r, original);
            else assertRule(r, ALREADY_REVERSED);
        }
        assertZero(x.codes());
    }

    @Test
    @DisplayName("REQ: concurrent-reversals-one — a reversal racing a same-key request that fails (a body, or an unknown entry) is never blocked by it (10 rounds): always 201 without the replay header")
    void failingSameKeyRequestDoesNotBlock() throws Exception {
        for (int round = 0; round < 10; round++) {
            Posted x = postTwoLines(100);
            String key = newKey();
            boolean withBody = round % 2 == 0;
            Callable<Response> failing = withBody
                    ? () -> reverseWithBody(x.id(), key, "application/json", "{}")
                    : () -> reverse(UUID.randomUUID().toString(), key);

            List<Response> rs = simultaneously(List.of(failing, () -> reverse(x.id(), key)));

            assertProblem(rs.get(0), withBody ? 400 : 404);
            assertReversalOf(rs.get(1), x, key);
            assertNotReplayed(rs.get(1));
            assertZero(x.codes());
        }
    }

    @Test
    @DisplayName("REQ: concurrent-reversals-one — a reversal and an ordinary post racing under the same key (5 rounds): exactly one 201, the other 422 idempotency-key-reused; the loser writes nothing")
    void reversalRacesOrdinaryPostSameKey() throws Exception {
        for (int round = 0; round < 5; round++) {
            Posted x = postTwoLines(100);
            String c = newAccount("REVENUE");
            String d = newAccount("EXPENSE");
            String key = newKey();

            List<Response> rs = simultaneously(List.of(
                    () -> reverse(x.id(), key), () -> post(key, entry(line(c, 250), line(d, -250)))));

            Response reversal = rs.get(0);
            Response ordinary = rs.get(1);
            observe("round " + round + " reversal vs post", reversal.statusCode(), "", "post " + ordinary.statusCode());
            if (reversal.statusCode() == 201) {
                assertReversalOf(reversal, x, key);
                assertReused(ordinary);
                assertZero(x.codes());
                assertUntouched(c, d);
            } else {
                assertReused(reversal);
                assertThat(ordinary.statusCode()).as("round %d: the post wins (body %s)", round, ordinary.asString()).isEqualTo(201);
                assertBalances(x.codes(), x.amounts());
                assertBalance(c, 250);
            }
        }
    }

    @Test
    @DisplayName("REQ: concurrent-reversals-one — reversals of two different entries racing under the same key (5 rounds): exactly one 201, the other 422 idempotency-key-reused")
    void reversalsOfTwoEntriesSameKey() throws Exception {
        for (int round = 0; round < 5; round++) {
            Posted a = postTwoLines(100);
            Posted b = postTwoLines(200);
            String key = newKey();

            List<Response> rs = simultaneously(List.of(() -> reverse(a.id(), key), () -> reverse(b.id(), key)));

            boolean aWon = rs.get(0).statusCode() == 201;
            assertReversalOf(rs.get(aWon ? 0 : 1), aWon ? a : b, key);
            assertReused(rs.get(aWon ? 1 : 0));
            if (aWon) {
                assertZero(a.codes());
                assertBalances(b.codes(), b.amounts());
            } else {
                assertBalances(a.codes(), a.amounts());
                assertZero(b.codes());
            }
        }
    }

    // ------------------------------------------------------------------ rejected-reversal-keeps-key

    @Test
    @DisplayName("REQ: rejected-reversal-keeps-key — a key rejected with 422 entry-already-reversed then posts a valid ordinary entry: 201 without Idempotent-Replayed")
    void keyFromAlreadyReversedRejectionPostsEntry() {
        Posted x = postTwoLines(100);
        reverseOk(x.id());
        String key = newKey();
        assertRule(reverse(x.id(), key), ALREADY_REVERSED);
        String c = newAccount("ASSET");
        String d = newAccount("LIABILITY");

        Response r = postOk(key, entry(line(c, 75), line(d, -75)));

        assertNotReplayed(r);
        assertBalance(c, 75);
    }

    @Test
    @DisplayName("REQ: rejected-reversal-keeps-key — a key rejected with 404 (unknown entry) then reverses an existing entry: 201 without Idempotent-Replayed")
    void keyFrom404ReversesEntry() {
        String key = newKey();
        assertProblem(reverse(UUID.randomUUID().toString(), key), 404);
        Posted x = postTwoLines(100);

        Response r = reverse(x.id(), key);

        assertReversalOf(r, x, key);
        assertNotReplayed(r);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"400 body present", "400 entry_id without hyphens", "400 two Idempotency-Key headers",
            "404 unknown entry", "422 cannot-reverse-a-reversal", "422 entry-already-reversed"})
    @DisplayName("REQ: rejected-reversal-keeps-key — a key whose only use was a reversal rejected for the stated reason is unused: it then reverses a fresh entry, 201 without Idempotent-Replayed")
    void rejectedReversalLeavesKeyUnused(String kind) {
        Posted target = postTwoLines(100);
        String key = newKey();
        switch (kind) {
            case "400 body present" ->
                    assertProblem(reverseWithBody(target.id(), key, "application/json", entryOf(target.codes(), target.negated())), 400);
            case "400 entry_id without hyphens" -> assertProblem(reverseAtSegment(target.id().replace("-", ""), key), 400);
            case "400 two Idempotency-Key headers" -> assertRawProblem(rawPostTo(reversalPath(target.id()), null,
                    concat(keyHeader(latin1(key)), keyHeader(latin1(newKey()))), null), 400);
            case "404 unknown entry" -> assertProblem(reverse(UUID.randomUUID().toString(), key), 404);
            case "422 cannot-reverse-a-reversal" -> assertRule(reverse(idOf(reverseOk(target.id())), key), CANNOT_REVERSE_REVERSAL);
            case "422 entry-already-reversed" -> {
                reverseOk(target.id());
                assertRule(reverse(target.id(), key), ALREADY_REVERSED);
            }
            default -> throw new IllegalArgumentException(kind);
        }
        Posted fresh = postTwoLines(300);

        Response r = reverse(fresh.id(), key);

        assertReversalOf(r, fresh, key);
        assertNotReplayed(r);
        assertZero(fresh.codes());
    }

    // ------------------------------------------------------------------ reversal-errors-problem-json

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"400 body present", "400 non-canonical entry_id", "400 missing Idempotency-Key",
            "400 Idempotency-Key too long", "404 unknown entry", "422 entry-already-reversed",
            "422 cannot-reverse-a-reversal", "422 idempotency-key-reused"})
    @DisplayName("REQ: reversal-errors-problem-json — every reversal error is application/problem+json with a matching status, a non-blank detail, snake_case members, the right type, and is never 409")
    void everyErrorIsProblemJson(String kind) {
        Posted x = postTwoLines(100);
        Response r = switch (kind) {
            case "400 body present" -> reverseWithBody(x.id(), newKey(), "application/json", "{}");
            case "400 non-canonical entry_id" -> reverseAtSegment(x.id().replace("-", ""), newKey());
            case "400 missing Idempotency-Key" -> given().when().post(REVERSAL, x.id());
            case "400 Idempotency-Key too long" -> reverse(x.id(), padKey(256));
            case "404 unknown entry" -> reverse(UUID.randomUUID().toString(), newKey());
            case "422 entry-already-reversed" -> {
                reverseOk(x.id());
                yield reverse(x.id(), newKey());
            }
            case "422 cannot-reverse-a-reversal" -> reverse(idOf(reverseOk(x.id())), newKey());
            case "422 idempotency-key-reused" -> reverse(x.id(), postTwoLines(50).key());
            default -> throw new IllegalArgumentException(kind);
        };
        int status = Integer.parseInt(kind.substring(0, 3));

        assertThat(r.statusCode()).as("never 409 (body %s)", r.asString()).isNotEqualTo(409);
        assertProblem(r, status);
        assertSnakeCaseKeys(r.jsonPath().getMap(""), "problem body");
        assertNotReplayed(r);
        if (status == 422) {
            assertThat(typeOf(r)).as("problem type (body %s)", r.asString()).isEqualTo(switch (kind) {
                case "422 entry-already-reversed" -> ALREADY_REVERSED;
                case "422 cannot-reverse-a-reversal" -> CANNOT_REVERSE_REVERSAL;
                default -> KEY_REUSED;
            });
        } else {
            assertDefaultType(r);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"missing Idempotency-Key", "Idempotency-Key too long", "body present", "non-canonical entry_id"})
    @DisplayName("REQ: reversal-errors-problem-json — a 400's detail describes what was wrong (mentions the header, the body, or the entry id)")
    void badRequestDetailDescribesProblem(String kind) {
        Posted x = postTwoLines(100);
        Response r;
        List<String> anyOf;
        switch (kind) {
            case "missing Idempotency-Key" -> {
                r = given().when().post(REVERSAL, x.id());
                anyOf = List.of("idempotency");
            }
            case "Idempotency-Key too long" -> {
                r = reverse(x.id(), padKey(256));
                anyOf = List.of("idempotency");
            }
            case "body present" -> {
                r = reverseWithBody(x.id(), newKey(), "application/json", "{}");
                anyOf = List.of("body");
            }
            case "non-canonical entry_id" -> {
                r = reverseAtSegment(x.id().replace("-", ""), newKey());
                anyOf = List.of("entry_id", "entry id", "uuid", "identifier");
            }
            default -> throw new IllegalArgumentException(kind);
        }

        String detail = assertProblem(r, 400).toLowerCase(Locale.ROOT);

        observe("400 detail for " + kind, r);
        assertThat(anyOf.stream().anyMatch(detail::contains))
                .as("detail '%s' should mention one of %s", detail, anyOf).isTrue();
    }

    @Test
    @DisplayName("REQ: reversal-errors-problem-json — the 404 for an unknown entry carries a human-readable detail")
    void notFoundDetail() {
        String id = UUID.randomUUID().toString();

        Response r = reverse(id, newKey());

        observe("404 detail", r);
        assertProblem(r, 404);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE"})
    @DisplayName("REQ: reversal-errors-problem-json — another method on /journal-entries/{entry_id}/reversal (spec silent) is 404/405 problem+json and reverses nothing")
    void otherMethodsOnReversal(String method) {
        Posted x = postTwoLines(100);

        Response r = given().header(IDEMPOTENCY_KEY, newKey()).when().request(method, REVERSAL, x.id());

        observe(method + " /journal-entries/{entry_id}/reversal", r);
        assertProblemIn(r, 404, 405);
        assertNotReversed(x);
    }

    @Test
    @DisplayName("REQ: reversal-errors-problem-json — Accept: application/xml on a rejected reversal still yields a problem body or 406, never an empty body or a 5xx")
    void errorWithXmlAccept() {
        Posted x = postTwoLines(100);
        reverseOk(x.id());

        Response r = given().accept("application/xml").header(IDEMPOTENCY_KEY, newKey()).when().post(REVERSAL, x.id());

        observe("Accept xml on a second reversal", r);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isIn(406, 422);
        assertThat(r.asString()).as("an error must carry a body").isNotBlank();
        assertZero(x.codes());
    }

    // ------------------------------------------------------------------ ledger-rule-problem-types

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — the two reversal rules and the key rule answer with three distinct, exact types")
    void reversalRuleTypesDistinct() {
        Posted x = postTwoLines(100);
        String reversal = idOf(reverseOk(x.id()));
        Posted other = postTwoLines(100);

        String already = typeOf(reverse(x.id(), newKey()));
        String cannot = typeOf(reverse(reversal, newKey()));
        String reused = typeOf(reverse(other.id(), x.key()));

        assertThat(already).as("entry-already-reversed type").isEqualTo(ALREADY_REVERSED);
        assertThat(cannot).as("cannot-reverse-a-reversal type").isEqualTo(CANNOT_REVERSE_REVERSAL);
        assertThat(reused).as("idempotency-key-reused type").isEqualTo(KEY_REUSED);
        assertThat(new HashSet<>(List.of(already, cannot, reused))).as("no two rules share a type").hasSize(3);
    }

    // ------------------------------------------------------------------ helpers

    /** An entry posted as a precondition: its first response, its key, and what was sent. */
    record Posted(Response response, String key, List<String> codes, List<BigInteger> amounts) {
        String id() {
            return response.jsonPath().getString("entry_id");
        }

        List<BigInteger> negated() {
            return amounts.stream().map(BigInteger::negate).toList();
        }
    }

    Posted postEntry(List<String> codes, List<BigInteger> amounts) {
        String key = newKey();
        return new Posted(postOk(key, entryOf(codes, amounts)), key, codes, amounts);
    }

    /** [amount, -amount] on a fresh ASSET and a fresh LIABILITY account. */
    Posted postTwoLines(long amount) {
        return postEntry(List.of(newAccount("ASSET"), newAccount("LIABILITY")), cents(amount, -amount));
    }

    Response reverse(String entryId, String key) {
        return given().header(IDEMPOTENCY_KEY, key).when().post(REVERSAL, entryId);
    }

    Response reverseOk(String entryId, String key) {
        Response r = reverse(entryId, key);
        assertThat(r.statusCode())
                .as("reversal of %s should be 201 but was %d (%s): %s", entryId, r.statusCode(), r.contentType(), r.asString())
                .isEqualTo(201);
        return r;
    }

    Response reverseOk(String entryId) {
        return reverseOk(entryId, newKey());
    }

    Response reverseWithBody(String entryId, String key, String contentType, String body) {
        return given().contentType(contentType).header(IDEMPOTENCY_KEY, key)
                .body(body.getBytes(StandardCharsets.UTF_8)).when().post(REVERSAL, entryId);
    }

    /** A reversal whose path segment is sent exactly as written: percent-encode it yourself. */
    Response reverseAtSegment(String segment, String key) {
        return given().urlEncodingEnabled(false).header(IDEMPOTENCY_KEY, key).when().post(reversalPath(segment));
    }

    static String reversalPath(String segment) {
        return ENTRIES + "/" + segment + "/reversal";
    }

    static String idOf(Response r) {
        return r.jsonPath().getString("entry_id");
    }

    /** The response is the 201 reversal of {@code original}, created under {@code key}. */
    static void assertReversalOf(Response r, Posted original, String key) {
        assertThat(r.statusCode())
                .as("reversal should be 201 but was %d (%s): %s", r.statusCode(), r.contentType(), r.asString())
                .isEqualTo(201);
        assertThat(r.contentType()).as("Content-Type of a reversal").startsWith("application/json");
        assertReversalJson(r.jsonPath(), r.asString(), original, key);
    }

    static void assertRawReversalOf(RawResponse r, Posted original, String key) {
        assertThat(r.status()).as("reversal should be 201 (Content-Type %s, body %s)", r.contentType(), r.body()).isEqualTo(201);
        assertThat(r.contentType()).as("Content-Type of a reversal").startsWith("application/json");
        assertReversalJson(JsonPath.from(r.body()), r.body(), original, key);
    }

    static void assertReversalJson(JsonPath p, String body, Posted original, String key) {
        assertThat(p.getString("reverses_entry_id"))
                .as("reverses_entry_id must be the original's entry_id (body %s)", body).isEqualTo(original.id());
        assertThat(p.getString("entry_id")).as("the reversal's own entry_id").isNotBlank().isNotEqualTo(original.id());
        assertThat(p.getString("idempotency_key")).as("idempotency_key must echo the key sent").isEqualTo(key);
        List<Map<String, Object>> lines = p.getList("lines");
        List<BigInteger> expected = original.negated();
        assertThat(lines).as("reversal lines (body %s)", body).isNotNull().hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> l = lines.get(i);
            assertThat(integer(l.get("line_number"), "line_number"))
                    .as("line_number at position %d", i).isEqualTo(BigInteger.valueOf(i + 1));
            assertThat(l.get("account_code")).as("account_code of line %d", i + 1).isEqualTo(original.codes().get(i));
            assertThat(integer(l.get("amount_cents"), "amount_cents"))
                    .as("amount_cents of line %d: the original's, negated", i + 1).isEqualTo(expected.get(i));
        }
    }

    /** "The entry is not reversed": balances still show it, and its reversal still succeeds now. */
    void assertNotReversed(Posted x) {
        assertBalances(x.codes(), x.amounts());
        String key = newKey();
        assertReversalOf(reverse(x.id(), key), x, key);
        assertZero(x.codes());
    }

    /** A 201 reversal-or-replay set from one key: exactly one original, every other an identical replay. */
    static void assertOneOriginalRestReplays(List<Response> rs, Posted x, String key, String what) {
        Response original = null;
        for (Response r : rs) {
            assertThat(r.statusCode()).as("%s: every same-key reversal is 201 (body %s)", what, r.asString()).isEqualTo(201);
            if (r.headers().getValues(REPLAYED).isEmpty()) {
                assertThat(original).as("%s: only one response may lack %s", what, REPLAYED).isNull();
                original = r;
            }
        }
        assertThat(original).as("%s: exactly one response must be the original, without %s", what, REPLAYED).isNotNull();
        assertReversalOf(original, x, key);
        for (Response r : rs) {
            if (r != original) assertReplayOf(r, original);
        }
    }

    /** Exactly one original 201 reversal; every other response 422 entry-already-reversed. */
    static void assertOneCreatedRestAlreadyReversed(List<Response> rs, Posted x, String what) {
        Response created = null;
        for (Response r : rs) {
            if (r.statusCode() == 201) {
                assertThat(created).as("%s: only one reversal may be created (second: %s)", what, r.asString()).isNull();
                created = r;
            } else {
                assertThat(typeOf(r)).as("%s: the other requests are entry-already-reversed (status %d, body %s)",
                        what, r.statusCode(), r.asString()).isEqualTo(ALREADY_REVERSED);
                assertThat(r.statusCode()).as("%s: status", what).isEqualTo(422);
            }
        }
        assertThat(created).as("%s: exactly one reversal must be created", what).isNotNull();
        assertNotReplayed(created);
        assertReversalOf(created, x, created.jsonPath().getString("idempotency_key"));
    }

    /** Sends every request at the same moment; responses come back in submission order. */
    static List<Response> simultaneously(List<Callable<Response>> requests) throws Exception {
        int n = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (Callable<Response> request : requests) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.call();
                }));
            }
            ready.await();
            start.countDown();
            List<Response> out = new ArrayList<>();
            for (Future<Response> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    List<BigInteger> balancesOf(List<String> codes) {
        return codes.stream().map(this::balanceOf).toList();
    }

    void assertBalances(List<String> codes, List<BigInteger> expected) {
        for (int i = 0; i < codes.size(); i++) assertBalance(codes.get(i), expected.get(i));
    }

    void assertZero(List<String> codes) {
        for (String code : codes) assertBalance(code, 0);
    }

    /** Asserts the members that may carry an identifier are only entry_id and reverses_entry_id, at every level. */
    static void assertIdentifiersOnly(Response r, String where) {
        assertNoInternalId(r.jsonPath().getMap(""), Set.of("entry_id", "reverses_entry_id"), where);
        List<Map<String, Object>> lines = r.jsonPath().getList("lines");
        for (Map<String, Object> l : lines) assertNoInternalId(l, Set.of(), where + " line");
    }

    static Instant timestamp(Response r) {
        String createdAt = r.jsonPath().getString("created_at");
        try {
            return OffsetDateTime.parse(createdAt).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new AssertionError("created_at '" + createdAt + "' is not an ISO-8601 timestamp with offset", e);
        }
    }

    /** Neither the stored codes (whole, or their unique tail) nor the stored amount appear in {@code text}. */
    static void assertRevealsNothingOf(String text, String where, List<String> storedCodes) {
        for (String code : storedCodes) {
            assertThat(text).as("%s must not reveal the stored account_code %s", where, code)
                    .doesNotContain(code)
                    .doesNotContain(code.substring(code.indexOf(':') + 1));
        }
        assertThat(text).as("%s must not reveal the stored amount", where).doesNotContain(String.valueOf(STORED));
    }

    static String[] lines(List<String> codes, List<BigInteger> amounts) {
        String[] out = new String[codes.size()];
        for (int i = 0; i < out.length; i++) out[i] = line(codes.get(i), amounts.get(i));
        return out;
    }

    /** [1, -1, 2, -2, ..., n, -n]: balanced, non-zero, one line per account. */
    static List<BigInteger> pairs(int n) {
        List<BigInteger> amounts = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            amounts.add(BigInteger.valueOf(i));
            amounts.add(BigInteger.valueOf(-i));
        }
        return amounts;
    }

    /** The id with every letter in the other case (lower-case ids are upper-cased, and vice versa). */
    static String otherCase(String id) {
        String upper = id.toUpperCase(Locale.ROOT);
        String other = upper.equals(id) ? id.toLowerCase(Locale.ROOT) : upper;
        assertThat(other).as("precondition: entry_id %s has hex letters to change case", id).isNotEqualTo(id);
        return other;
    }

    /** Letters alternately upper- and lower-cased. */
    static String mixedCase(String id) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : id.toCharArray()) {
            if (Character.isLetter(c)) {
                sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upper = !upper;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The id with its first ASCII digit replaced by the same digit from another Unicode block (zero at {@code zero}). */
    static String withUnicodeDigit(String id, char zero) {
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c >= '0' && c <= '9') return id.substring(0, i) + (char) (zero + (c - '0')) + id.substring(i + 1);
        }
        throw new AssertionError("precondition: entry_id " + id + " has no decimal digit");
    }

    /** Percent-encodes, as UTF-8, every byte outside the RFC 3986 unreserved set. */
    static String pathSegment(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) sb.append((char) c);
            else sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
    }

    /** A unique visible-ASCII key of exactly {@code length} characters. */
    String padKey(int length) {
        String base = newKey();
        return base + "k".repeat(length - base.length());
    }

    static String allVisibleAscii() {
        StringBuilder s = new StringBuilder();
        for (char c = 0x21; c <= 0x7e; c++) s.append(c);
        return s.toString();
    }
}
