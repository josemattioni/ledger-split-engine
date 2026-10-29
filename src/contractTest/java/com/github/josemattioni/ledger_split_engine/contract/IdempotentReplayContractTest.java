package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Black-box contract for the idempotent-replay slice, written from
 * openspec/changes/idempotent-replay/specs/journal-entries/spec.md and the "Idempotency Contract"
 * of PROJECT-CONVENTIONS.md only. Endpoint: {@code POST /api/v1/journal-entries}.
 *
 * <p>Requirement slugs used in the display names:
 * <ul>
 *   <li>{@code idempotency-keys-unique}: "Idempotency keys are unique" (an identical retry replays)
 *   <li>{@code key-reuse-rejected}: "A key reused for a different request is rejected"
 *   <li>{@code only-success-consumes-key}: "Only successful posts consume an idempotency key"
 *   <li>{@code concurrent-same-key-one-entry}: "Concurrent posts with the same key produce one entry"
 *   <li>{@code idempotency-check-order}: "The idempotency check follows request validation and
 *       precedes account resolution"
 *   <li>{@code journal-errors-problem-json}, {@code ledger-rule-problem-types}: the modified
 *       requirements of those names
 * </ul>
 *
 * <p>"Writes nothing" and "exists once" are observed through balances: every account is fresh, so
 * a second write of the same entry would double them. "The original is unchanged" is observed the
 * same way. That the original still replays after a rejected reuse is its own test
 * ({@code rejectedReuseLeavesKeyBound}), so a replay defect does not cascade into every reuse test.
 *
 * <p>This spec retires the 409 for a repeated key. The earlier-slice tests that repeated a key were
 * updated to it in their own suites (JournalEntryContractTest, JournalEntryGuardsContractTest,
 * LedgerAppendOnlyContractTest).
 *
 * <p>Updated to later specs: harden-request-validation checks a line's account-code format before
 * the key (so a malformed code under a used key is {@code invalid-account-code}, not
 * {@code idempotency-key-reused}); entry-reversal adds {@code reverses_entry_id}, null for an
 * ordinary entry, to every entry representation.
 */
class IdempotentReplayContractTest extends JournalSliceContractSupport {

    /** A stored amount whose digits cannot plausibly occur in a key, code or wording by accident. */
    static final long STORED = 48_271_939L;

    // ------------------------------------------------------------------ idempotency-keys-unique

    @Test
    @DisplayName("REQ: idempotency-keys-unique — an identical retry is 201 with the identical body (entry_id, idempotency_key, created_at, lines) and Idempotent-Replayed: true; only the retry carries the header")
    void identicalRetryReplays() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 1500), line(b, -1500));

        Response first = postOk(key, body);
        Response retry = post(key, body);

        assertNotReplayed(first);
        assertReplayOf(retry, first);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a replay writes nothing: after an identical retry the balances count the entry once")
    void replayWritesNothing() {
        String a = newAccount("ASSET");
        String b = newAccount("REVENUE");
        String key = newKey();
        String body = entry(line(a, 4200), line(b, -4200));
        postOk(key, body);

        Response retry = post(key, body);

        assertThat(retry.statusCode()).as("retry status (body %s)", retry.asString()).isEqualTo(201);
        assertBalance(a, 4200);
        assertBalance(b, -4200);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — five successive identical retries each replay the original body; the balances still count the entry once")
    void repeatedRetriesReplay() {
        String a = newAccount("EXPENSE");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 77), line(b, -77));
        Response first = postOk(key, body);

        for (int i = 0; i < 5; i++) assertReplayOf(post(key, body), first);

        assertBalance(a, 77);
        assertBalance(b, -77);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a first post with a never-used key is 201 with no Idempotent-Replayed header at all (not even 'false')")
    void firstPostHasNoReplayHeader() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        assertNotReplayed(postOk(newKey(), entry(line(a, 10), line(b, -10))));
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a retry after a pause and ten unrelated posts is still replayed (keys never expire; 'arbitrarily old' can only be approximated black-box)")
    void laterRetryReplays() throws InterruptedException {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("ASSET");
        String d = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 900), line(b, -900));
        Response first = postOk(key, body);

        for (int i = 1; i <= 10; i++) postOk(entry(line(c, i), line(d, -i)));
        Thread.sleep(2_000);

        assertReplayOf(post(key, body), first);
        assertBalance(a, 900);
        assertBalance(b, -900);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a replayed nine-line entry returns its lines in line_number order with the original codes and amounts")
    void replayKeepsLineOrder() {
        List<String> codes = newAccounts(9);
        List<BigInteger> amounts = cents(5, -1, 7, -3, 2, -9, 4, -6, 1);
        String key = newKey();
        String body = entryOf(codes, amounts);
        postOk(key, body);

        Response retry = post(key, body);

        assertReplayed(retry);
        assertLines(retry, codes, amounts);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a 40-line entry on 40 distinct accounts replays the identical body")
    void longEntryReplays() {
        List<String> codes = newAccounts(40);
        List<BigInteger> amounts = pairs(20);
        String key = newKey();
        String body = entryOf(codes, amounts);
        Response first = postOk(key, body);

        assertReplayOf(post(key, body), first);
        assertBalance(codes.get(39), -20);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"line fields reordered", "extra whitespace", "escaped character in account_code", "charset parameter"})
    @DisplayName("REQ: idempotency-keys-unique — a retry whose JSON differs only in spelling, not in any line's account_code or amount_cents, is the same request and is replayed")
    void spellingIrrelevantToReplay(String variant) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        Response first = postOk(key, entry(line(a, 300), line(b, -300)));

        Response retry = switch (variant) {
            case "line fields reordered" -> post(key,
                    entry(json("amount_cents", 300, "account_code", a), json("amount_cents", -300, "account_code", b)));
            case "extra whitespace" -> post(key,
                    "{\n  \"lines\" : [\n    " + line(a, 300) + " ,\n    " + line(b, -300) + "\n  ]\n}\n");
            // the underscore of the second code written as a JSON unicode escape: the same string once parsed
            case "escaped character in account_code" -> post(key,
                    entry(line(a, 300), "{\"account_code\":" + quote(b).replace("_", "\\u005f") + ",\"amount_cents\":-300}"));
            case "charset parameter" -> given().contentType("application/json; charset=UTF-8").header(IDEMPOTENCY_KEY, key)
                    .body(entry(line(a, 300), line(b, -300))).when().post(ENTRIES);
            default -> throw new IllegalArgumentException(variant);
        };

        observe("retry with " + variant, retry);
        assertReplayOf(retry, first);
        assertBalance(a, 300);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"idempotency-key", "IDEMPOTENCY-KEY"})
    @DisplayName("REQ: idempotency-keys-unique — the retry's header name is matched case-insensitively (HTTP semantics): the retry is replayed")
    void retryHeaderNameCaseInsensitive(String headerName) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 60), line(b, -60));
        Response first = postOk(key, body);

        Response retry = given().contentType("application/json").header(headerName, key).body(body).when().post(ENTRIES);

        assertReplayOf(retry, first);
        assertBalance(a, 60);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a replayed response is application/json, snake_case at every level, and exposes no identifier but entry_id and reverses_entry_id (null for an ordinary entry)")
    void replayBodyShape() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 15), line(b, -15));
        postOk(key, body);

        Response retry = post(key, body);

        assertThat(retry.statusCode()).as("retry status (body %s)", retry.asString()).isEqualTo(201);
        assertThat(retry.contentType()).as("Content-Type of a replay").startsWith("application/json");
        Map<String, Object> map = retry.jsonPath().getMap("");
        assertSnakeCaseKeys(map, "replayed entry");
        assertNoInternalId(map, Set.of("entry_id", "reverses_entry_id"), "replayed entry");
        assertThat(map).as("replayed ordinary entry").containsEntry("reverses_entry_id", null);
        List<Map<String, Object>> lines = retry.jsonPath().getList("lines");
        for (Map<String, Object> l : lines) {
            assertSnakeCaseKeys(l, "replayed line");
            assertNoInternalId(l, Set.of(), "replayed line");
        }
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — an entry at the permitted amount limit replays with its exact amounts")
    void replayAtAmountLimit() {
        BigInteger limit = permittedLimit();
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        List<BigInteger> amounts = List.of(limit, limit.negate());
        String body = entryOf(List.of(a, b), amounts);
        Response first = postOk(key, body);

        Response retry = post(key, body);

        assertReplayOf(retry, first);
        assertLines(retry, List.of(a, b), amounts);
        assertBalance(a, limit);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a 255-character key (the longest allowed) replays and is echoed verbatim")
    void maxLengthKeyReplays() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = padKey(255);
        String body = entry(line(a, 100), line(b, -100));
        Response first = postOk(key, body);

        Response retry = post(key, body);

        assertReplayOf(retry, first);
        assertThat(retry.jsonPath().getString("idempotency_key")).isEqualTo(key);
        assertBalance(a, 100);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a key holding every visible ASCII character (quotes, backslash, angle brackets, percent) replays the identical body")
    void allVisibleAsciiKeyReplays() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = uniqueTail() + allVisibleAscii();
        String body = entry(line(a, 100), line(b, -100));

        RawResponse first = rawPostWithKey(latin1(key), body);
        RawResponse retry = rawPostWithKey(latin1(key), body);

        assertThat(first.status()).as("first post (body %s)", first.body()).isEqualTo(201);
        assertThat(first.headers()).as("first post headers").doesNotContainKey(REPLAYED.toLowerCase(Locale.ROOT));
        assertThat(retry.status()).as("retry (body %s)", retry.body()).isEqualTo(201);
        assertThat(retry.headers().get(REPLAYED.toLowerCase(Locale.ROOT))).as("retry's %s header", REPLAYED).isEqualTo("true");
        assertThat(JsonPath.from(retry.body()).getMap(""))
                .as("replayed body%n first:  %s%n replay: %s", first.body(), retry.body())
                .isEqualTo(JsonPath.from(first.body()).getMap(""));
        assertBalance(a, 100);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a 256-character key extending a used 255-character key is 400, never matched to the used key by truncation")
    void overLongKeyNotTruncatedToUsedKey() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = padKey(255);
        String body = entry(line(a, 100), line(b, -100));
        postOk(key, body);

        Response r = post(key + "k", body);

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertBalance(a, 100);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — a strict prefix of a used key is a different, unused key: identical lines post a second entry without the replay header")
    void prefixOfUsedKeyIsNewKey() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 100), line(b, -100));
        Response first = postOk(key, body);

        Response second = postOk(key.substring(0, key.length() - 1), body);

        assertNotReplayed(second);
        assertThat(second.jsonPath().getString("entry_id")).isNotEqualTo(first.jsonPath().getString("entry_id"));
        assertBalance(a, 200);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — keys are compared exactly: the upper-cased variant of a used key posts identical lines as a second entry, without the replay header")
    void caseVariantOfUsedKeyIsNewKey() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = "case-" + uniqueTail();
        String body = entry(line(a, 100), line(b, -100));
        postOk(key, body);

        Response second = postOk(key.toUpperCase(Locale.ROOT), body);

        assertNotReplayed(second);
        assertBalance(a, 200);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — same key with different lines is 422 idempotency-key-reused, never 409; nothing new is created and the original's balances are unchanged")
    void repeatedKeyDifferentLinesIs422() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String d = newAccount("EXPENSE");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        Response r = post(key, entry(line(c, 999), line(d, -999)));

        assertThat(r.statusCode()).as("a repeated key is never 409 (body %s)", r.asString()).isNotEqualTo(409);
        assertReused(r);
        assertBalance(a, 300);
        assertBalance(b, -300);
        assertUntouched(c, d);
    }

    // ------------------------------------------------------------------ key-reuse-rejected

    @Test
    @DisplayName("REQ: key-reuse-rejected — same key, same two accounts, a different (still balanced) amount: 422 idempotency-key-reused; balances unchanged, nothing created")
    void differentAmountRejected() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        assertReused(post(key, entry(line(a, 500), line(b, -500))));

        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — same key with the same lines in a different order: 422 idempotency-key-reused (comparison is order-sensitive), nothing created")
    void reorderedLinesRejected() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        assertReused(post(key, entry(line(b, -300), line(a, 300))));

        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — a two-line entry, then a three-line entry with the same key: 422 idempotency-key-reused, nothing created")
    void extraLineRejected() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        assertReused(post(key, entry(line(a, 300), line(b, -250), line(c, -50))));

        assertBalance(a, 300);
        assertBalance(b, -300);
        assertUntouched(c);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"amounts swapped between the lines", "accounts swapped, amounts kept", "one account replaced",
            "one centavo more on each side", "the original lines plus two more", "the original lines minus the last two"})
    @DisplayName("REQ: key-reuse-rejected — a retry that differs from the original only by the stated change is 422 idempotency-key-reused (lines compared per line_number by count, account_code and amount_cents); nothing created")
    void lineDifferencesRejected(String variant) {
        List<String> acc = newAccounts(4);
        String a = acc.get(0), b = acc.get(1), c = acc.get(2), d = acc.get(3);
        List<String> origCodes = List.of(a, b);
        List<BigInteger> origAmounts = cents(300, -300);
        String retry;
        switch (variant) {
            case "amounts swapped between the lines" -> retry = entry(line(a, -300), line(b, 300));
            case "accounts swapped, amounts kept" -> retry = entry(line(b, 300), line(a, -300));
            case "one account replaced" -> retry = entry(line(a, 300), line(c, -300));
            case "one centavo more on each side" -> retry = entry(line(a, 301), line(b, -301));
            case "the original lines plus two more" ->
                    retry = entry(line(a, 300), line(b, -300), line(c, 50), line(d, -50));
            case "the original lines minus the last two" -> {
                origCodes = List.of(a, b, c, d);
                origAmounts = cents(300, -300, 50, -50);
                retry = entry(line(a, 300), line(b, -300));
            }
            default -> throw new IllegalArgumentException(variant);
        }
        String key = newKey();
        postOk(key, entryOf(origCodes, origAmounts));

        assertReused(post(key, retry));

        for (String code : acc) {
            int i = origCodes.indexOf(code);
            assertBalance(code, i < 0 ? BigInteger.ZERO : origAmounts.get(i));
        }
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — a 40-line retry identical except for the amounts on lines 39 and 40 is 422 idempotency-key-reused")
    void tailDifferenceRejected() {
        List<String> codes = newAccounts(40);
        List<BigInteger> original = pairs(20);
        List<BigInteger> retry = new ArrayList<>(original);
        retry.set(38, BigInteger.valueOf(21));
        retry.set(39, BigInteger.valueOf(-21));
        String key = newKey();
        postOk(key, entryOf(codes, original));

        assertReused(post(key, entryOf(codes, retry)));

        assertBalance(codes.get(38), 20);
        assertBalance(codes.get(39), -20);
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — the detail names the key and reveals neither the stored entry's account codes nor its amounts")
    void detailNamesKeyNotStoredPayload() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String d = newAccount("EXPENSE");
        String key = newKey();
        postOk(key, entry(line(a, STORED), line(b, -STORED)));

        String detail = assertReused(post(key, entry(line(c, 5150), line(d, -5150))));

        assertThat(detail).as("detail must name the key").contains(key);
        assertRevealsNothingOf(detail, "detail", a, b);
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — when the retry reuses the original's accounts with a new amount, the detail still never reveals the stored amount")
    void detailHidesStoredAmountOnSameAccounts() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, STORED), line(b, -STORED)));

        String detail = assertReused(post(key, entry(line(a, 5150), line(b, -5150))));

        assertThat(detail).as("detail must name the key").contains(key);
        assertThat(detail).as("detail must not reveal the stored amount").doesNotContain(String.valueOf(STORED));
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — no member of the 422 problem body, not only detail, echoes the stored entry's account codes or amounts (conventions: never echoes the stored payload)")
    void problemBodyHidesStoredPayload() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String d = newAccount("EXPENSE");
        String key = newKey();
        postOk(key, entry(line(a, STORED), line(b, -STORED)));

        Response r = post(key, entry(line(c, 5150), line(d, -5150)));

        assertReused(r);
        assertRevealsNothingOf(r.asString(), "problem body", a, b);
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — a key with quotes, backslash, angle brackets and other visible punctuation is named verbatim in the detail")
    void detailNamesUnusualKey() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = uniqueTail() + "\"\\<>%&{}'`";
        RawResponse first = rawPostWithKey(latin1(key), entry(line(a, 300), line(b, -300)));
        assertThat(first.status()).as("precondition: first post (body %s)", first.body()).isEqualTo(201);

        RawResponse r = rawPostWithKey(latin1(key), entry(line(a, 500), line(b, -500)));

        String detail = assertRawProblem(r, 422);
        assertThat(JsonPath.from(r.body()).getString("type")).as("problem type").isEqualTo(KEY_REUSED);
        assertThat(detail).as("detail must name the key verbatim").contains(key);
        assertBalance(a, 300);
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — a 255-character key is named in full in the detail, not truncated")
    void detailNamesLongKey() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = padKey(255);
        postOk(key, entry(line(a, 300), line(b, -300)));

        String detail = assertReused(post(key, entry(line(a, 500), line(b, -500))));

        assertThat(detail).as("detail must name the whole key").contains(key);
    }

    @Test
    @DisplayName("REQ: key-reuse-rejected — a rejected reuse leaves the key bound to the original: the original lines still replay the original body, and the other lines are rejected again")
    void rejectedReuseLeavesKeyBound() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String original = entry(line(a, 300), line(b, -300));
        String other = entry(line(a, 500), line(b, -500));
        Response first = postOk(key, original);

        assertReused(post(key, other));
        assertReplayOf(post(key, original), first);
        assertReused(post(key, other));

        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    // ------------------------------------------------------------------ journal-errors-problem-json

    @Test
    @DisplayName("REQ: journal-errors-problem-json — the key-reuse rejection is application/problem+json with status 422, a non-blank detail, snake_case members and no Idempotent-Replayed header")
    void reuseRejectionShape() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        Response r = post(key, entry(line(a, 400), line(b, -400)));

        assertProblem(r, 422);
        assertNotReplayed(r);
        assertSnakeCaseKeys(r.jsonPath().getMap(""), "problem body");
    }

    // ------------------------------------------------------------------ ledger-rule-problem-types

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — a valid entry under a key used by an entry with different lines is 422 with type exactly urn:ledger-split-engine:problem:idempotency-key-reused")
    void reusedKeyType() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String d = newAccount("EXPENSE");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        Response r = post(key, entry(line(c, 300), line(d, -300)));

        assertProblem(r, 422);
        assertThat(typeOf(r)).as("problem type (body %s)", r.asString()).isEqualTo(KEY_REUSED);
        assertUntouched(c, d);
    }

    // ------------------------------------------------------------------ only-success-consumes-key

    @Test
    @DisplayName("REQ: only-success-consumes-key — after a 422 unknown-account rejection, a valid entry with the same key is 201 without Idempotent-Replayed and exists once")
    void keyFromUnknownAccountRejectionIsReusable() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        Response rejected = post(key, entry(line(a, 100), line(uniqueCode(), -100)));
        assertRule(rejected, UNKNOWN_ACCOUNT);

        Response r = postOk(key, entry(line(a, 250), line(b, -250)));

        assertNotReplayed(r);
        assertBalance(a, 250);
        assertBalance(b, -250);
    }

    @Test
    @DisplayName("REQ: only-success-consumes-key — after a 400 malformed-body rejection, a valid entry with the same key is 201 without Idempotent-Replayed")
    void keyFromMalformedRejectionIsReusable() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        assertProblem(post(key, "{\"lines\":[" + line(a, 100)), 400);

        Response r = postOk(key, entry(line(a, 100), line(b, -100)));

        assertNotReplayed(r);
        assertBalance(a, 100);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"400 malformed JSON", "400 line missing amount_cents", "400 unknown property",
            "400 camelCase accountCode", "400 amount_cents as a string", "415 text/plain", "422 unbalanced-entry",
            "422 degenerate-entry: single line", "422 degenerate-entry: zero amount", "422 degenerate-entry: repeated account",
            "422 amount-limit-exceeded", "422 unknown-account"})
    @DisplayName("REQ: only-success-consumes-key — a key whose only post was rejected for the stated reason is unused: a different valid entry with it is 201 without Idempotent-Replayed, posted once")
    void rejectedPostLeavesKeyUnused(String kind) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String key = newKey();
        Response rejected = switch (kind) {
            case "400 malformed JSON" -> post(key, "{\"lines\":[" + line(a, 100));
            case "400 line missing amount_cents" -> post(key, entry(line(a, 100), json("account_code", b)));
            case "400 unknown property" -> post(key, json("memo", "x", "lines", array(line(a, 100), line(b, -100))));
            case "400 camelCase accountCode" -> post(key, entry(line(a, 100), json("accountCode", b, "amount_cents", -100)));
            case "400 amount_cents as a string" ->
                    post(key, entry(line(a, 100), json("account_code", b, "amount_cents", "-100")));
            case "415 text/plain" -> given().contentType("text/plain").header(IDEMPOTENCY_KEY, key)
                    .body(entry(line(a, 100), line(b, -100)).getBytes(StandardCharsets.UTF_8)).when().post(ENTRIES);
            case "422 unbalanced-entry" -> post(key, entry(line(a, 100), line(b, -99)));
            case "422 degenerate-entry: single line" -> post(key, entry(line(a, 100)));
            case "422 degenerate-entry: zero amount" -> post(key, entry(line(a, 100), line(b, 0), line(c, -100)));
            case "422 degenerate-entry: repeated account" -> post(key, entry(line(a, 100), line(a, 50), line(b, -150)));
            case "422 amount-limit-exceeded" -> post(key, entry(line(a, LONG_MAX), line(b, LONG_MAX.negate())));
            case "422 unknown-account" -> post(key, entry(line(a, 100), line(uniqueCode(), -100)));
            default -> throw new IllegalArgumentException(kind);
        };
        String type = switch (kind) {
            case "422 unbalanced-entry" -> UNBALANCED;
            case "422 amount-limit-exceeded" -> AMOUNT_LIMIT;
            case "422 unknown-account" -> UNKNOWN_ACCOUNT;
            default -> kind.startsWith("422 degenerate-entry") ? DEGENERATE : null;
        };
        assertProblem(rejected, Integer.parseInt(kind.substring(0, 3)));
        if (type != null) assertThat(typeOf(rejected)).as("problem type (body %s)", rejected.asString()).isEqualTo(type);
        assertNotReplayed(rejected);

        Response r = postOk(key, entry(line(a, 700), line(c, -700)));

        assertNotReplayed(r);
        assertBalance(a, 700);
        assertBalance(b, 0);
        assertBalance(c, -700);
    }

    @Test
    @DisplayName("REQ: only-success-consumes-key — after a rejection and then a success with the same key, retrying the successful lines replays that entry (the key now belongs to it)")
    void keyBelongsToFirstSuccess() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        assertRule(post(key, entry(line(a, 100), line(b, -99))), UNBALANCED);
        String valid = entry(line(a, 100), line(b, -100));
        Response created = postOk(key, valid);

        assertReplayOf(post(key, valid), created);
        assertBalance(a, 100);
        assertBalance(b, -100);
    }

    // ------------------------------------------------------------------ concurrent-same-key-one-entry

    @Test
    @DisplayName("REQ: concurrent-same-key-one-entry — two simultaneous identical posts (5 rounds): both 201 with identical bodies, exactly one carries Idempotent-Replayed: true, one entry")
    void twoSimultaneousIdenticalPosts() throws Exception {
        for (int round = 0; round < 5; round++) {
            String a = newAccount("ASSET");
            String b = newAccount("LIABILITY");
            String body = entry(line(a, 100), line(b, -100));

            List<Response> rs = postConcurrently(newKey(), List.of(body, body));

            assertOneOriginalRestReplays(rs, "round " + round);
            assertBalance(a, 100);
            assertBalance(b, -100);
        }
    }

    @Test
    @DisplayName("REQ: concurrent-same-key-one-entry — twelve simultaneous identical posts: all 201 with one body, exactly eleven replays, one entry")
    void manySimultaneousIdenticalPosts() throws Exception {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String body = entry(line(a, 100), line(b, -100));

        List<Response> rs = postConcurrently(newKey(), Collections.nCopies(12, body));

        assertOneOriginalRestReplays(rs, "12 identical");
        assertBalance(a, 100);
        assertBalance(b, -100);
    }

    @Test
    @DisplayName("REQ: concurrent-same-key-one-entry — two simultaneous posts with different lines (5 rounds): one 201, the other 422 idempotency-key-reused, one entry")
    void twoSimultaneousDifferentPosts() throws Exception {
        for (int round = 0; round < 5; round++) {
            String a = newAccount("ASSET");
            String b = newAccount("LIABILITY");
            String c = newAccount("REVENUE");
            String d = newAccount("EXPENSE");
            List<String> bodies = List.of(entry(line(a, 100), line(b, -100)), entry(line(c, 200), line(d, -200)));

            List<Response> rs = postConcurrently(newKey(), bodies);

            int winner = assertOneCreatedRestReused(rs, "round " + round);
            assertBalance(a, winner == 0 ? 100 : 0);
            assertBalance(b, winner == 0 ? -100 : 0);
            assertBalance(c, winner == 1 ? 200 : 0);
            assertBalance(d, winner == 1 ? -200 : 0);
        }
    }

    @Test
    @DisplayName("REQ: concurrent-same-key-one-entry — twelve simultaneous posts with twelve different amounts: one 201, eleven 422 idempotency-key-reused, balances equal the winner's amount")
    void manySimultaneousDifferentPosts() throws Exception {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        List<String> bodies = new ArrayList<>();
        for (int i = 1; i <= 12; i++) bodies.add(entry(line(a, i), line(b, -i)));

        List<Response> rs = postConcurrently(newKey(), bodies);

        long amount = assertOneCreatedRestReused(rs, "12 different") + 1;
        assertBalance(a, amount);
        assertBalance(b, -amount);
    }

    @Test
    @DisplayName("REQ: concurrent-same-key-one-entry — six posts of one entry and six of another, simultaneously: one original 201, every post with the winner's lines replays it, every other is 422 idempotency-key-reused, one entry")
    void simultaneousMixedPosts() throws Exception {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String d = newAccount("EXPENSE");
        String first = entry(line(a, 100), line(b, -100));
        String second = entry(line(c, 200), line(d, -200));
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bodies.add(first);
            bodies.add(second);
        }

        List<Response> rs = postConcurrently(newKey(), bodies);

        Response original = null;
        String winner = null;
        for (int i = 0; i < rs.size(); i++) {
            Response r = rs.get(i);
            if (r.statusCode() == 201 && r.headers().getValues(REPLAYED).isEmpty()) {
                assertThat(original).as("only one response may be the original 201 (second: %s)", r.asString()).isNull();
                original = r;
                winner = bodies.get(i);
            }
        }
        assertThat(original).as("exactly one response must be the original 201 without %s", REPLAYED).isNotNull();
        for (int i = 0; i < rs.size(); i++) {
            Response r = rs.get(i);
            if (r == original) continue;
            if (bodies.get(i).equals(winner)) assertReplayOf(r, original);
            else assertReused(r);
        }
        boolean firstWon = winner.equals(first);
        assertBalance(a, firstWon ? 100 : 0);
        assertBalance(c, firstWon ? 0 : 200);
    }

    @Test
    @DisplayName("REQ: concurrent-same-key-one-entry — a valid post racing a same-key post that fails on an unknown account (15 rounds) is never blocked: always 201 without the replay header, posted once; the failing post is 422")
    void failedFirstPostDoesNotBlockWaitingPost() throws Exception {
        int unknownAccount = 0;
        int keyReused = 0;
        for (int round = 0; round < 15; round++) {
            String a = newAccount("ASSET");
            String b = newAccount("LIABILITY");
            String failing = entry(line(a, 100), line(uniqueCode(), -100));
            String valid = entry(line(a, 100), line(b, -100));
            List<String> bodies = round % 2 == 0 ? List.of(failing, valid) : List.of(valid, failing);

            List<Response> rs = postConcurrently(newKey(), bodies);

            Response f = rs.get(bodies.indexOf(failing));
            Response v = rs.get(bodies.indexOf(valid));
            assertThat(v.statusCode()).as("round %d: valid post (body %s)", round, v.asString()).isEqualTo(201);
            assertNotReplayed(v);
            assertProblem(f, 422);
            assertThat(typeOf(f)).as("round %d: failing post's type (body %s)", round, f.asString())
                    .isIn(UNKNOWN_ACCOUNT, KEY_REUSED);
            if (UNKNOWN_ACCOUNT.equals(typeOf(f))) unknownAccount++;
            else keyReused++;
            assertBalance(a, 100);
            assertBalance(b, -100);
        }
        observe("failing post outcomes over 15 races", 0, "",
                "unknown-account (failing post ran first or alone): " + unknownAccount
                        + ", idempotency-key-reused (valid post committed first): " + keyReused);
    }

    // ------------------------------------------------------------------ idempotency-check-order

    @Test
    @DisplayName("REQ: idempotency-check-order — an unbalanced entry under a used key is 422 unbalanced-entry, not idempotency-key-reused; original intact")
    void usedKeyOnUnbalancedEntry() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        Response r = post(key, entry(line(a, 300), line(b, -299)));

        assertRule(r, UNBALANCED);
        assertNotReplayed(r);
        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @Test
    @DisplayName("REQ: idempotency-check-order — an otherwise valid entry naming an unknown account under a key used by different lines is 422 idempotency-key-reused, not unknown-account")
    void usedKeyOnUnknownAccount() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        assertReused(post(key, entry(line(a, 300), line(uniqueCode(), -300))));

        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"upper-cased", "leading space", "trailing space"})
    @DisplayName("REQ: idempotency-check-order — a retry whose second account_code is the original's upper-cased or space-padded is 422 invalid-account-code (format is checked before the key): never a replay, never idempotency-key-reused or unknown-account")
    void accountCodeSpellingNotNormalized(String variant) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));
        String bad = switch (variant) {
            case "upper-cased" -> b.toUpperCase(Locale.ROOT);
            case "leading space" -> " " + b;
            case "trailing space" -> b + " ";
            default -> throw new IllegalArgumentException(variant);
        };

        Response r = post(key, entry(line(a, 300), line(bad, -300)));

        assertRule(r, INVALID_ACCOUNT_CODE);
        assertNotReplayed(r);
        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"empty", "whitespace-only"})
    @DisplayName("REQ: idempotency-check-order — a retry whose second account_code is blank is rejected before the key is looked up: 400, or 422 invalid-account-code; never a replay, never idempotency-key-reused")
    void blankAccountCodeUnderUsedKey(String variant) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));
        String bad = variant.equals("empty") ? "" : "   ";

        Response r = post(key, entry(line(a, 300), line(bad, -300)));

        observe("used key + account_code " + variant, r);
        assertProblemIn(r, 400, 422);
        if (r.statusCode() == 400) assertDefaultType(r);
        else assertThat(typeOf(r)).as("problem type (body %s)", r.asString()).isEqualTo(INVALID_ACCOUNT_CODE);
        assertNotReplayed(r);
        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @Test
    @DisplayName("REQ: idempotency-check-order — an otherwise valid entry naming an unknown account under a never-used key is 422 unknown-account")
    void newKeyOnUnknownAccount() {
        String a = newAccount("ASSET");
        Response r = post(newKey(), entry(line(a, 300), line(uniqueCode(), -300)));

        assertRule(r, UNKNOWN_ACCOUNT);
        assertNotReplayed(r);
        assertUntouched(a);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"empty lines", "single line", "zero-amount line", "repeated account", "amount one past the limit"})
    @DisplayName("REQ: idempotency-check-order — a used key on an entry breaking a rule that needs no stored data reports that rule, not idempotency-key-reused; original intact")
    void usedKeyStillValidatedFirst(String kind) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));
        String body;
        String type;
        switch (kind) {
            case "empty lines" -> {
                body = "{\"lines\":[]}";
                type = DEGENERATE;
            }
            case "single line" -> {
                body = entry(line(a, 300));
                type = DEGENERATE;
            }
            case "zero-amount line" -> {
                body = entry(line(a, 300), line(c, 0), line(b, -300));
                type = DEGENERATE;
            }
            case "repeated account" -> {
                body = entry(line(a, 300), line(b, -100), line(b, -200));
                type = DEGENERATE;
            }
            case "amount one past the limit" -> {
                BigInteger limit = permittedLimit();
                body = entryOf(List.of(a, b, c),
                        List.of(limit.add(BigInteger.ONE), limit.negate(), BigInteger.ONE.negate()));
                type = AMOUNT_LIMIT;
            }
            default -> throw new IllegalArgumentException(kind);
        }

        Response r = post(key, body);

        assertRule(r, type);
        assertNotReplayed(r);
        assertBalance(a, 300);
        assertBalance(b, -300);
        assertUntouched(c);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"malformed JSON", "lines null", "line missing amount_cents", "null account_code",
            "amount_cents as a string", "decimal amount_cents", "unknown top-level property", "unknown line property",
            "camelCase accountCode", "camelCase amountCents", "idempotency_key in the body", "entry_id in the body",
            "the first response sent back as the body"})
    @DisplayName("REQ: idempotency-check-order — a malformed retry of the original lines under the used key is 400 with the default type: never replayed, never idempotency-key-reused")
    void usedKeyOnMalformedRetry(String kind) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String original = entry(line(a, 300), line(b, -300));
        Response first = postOk(key, original);
        Raw lines = array(line(a, 300), line(b, -300));
        String body = switch (kind) {
            case "malformed JSON" -> original.substring(0, original.length() - 1);
            case "lines null" -> "{\"lines\":null}";
            case "line missing amount_cents" -> entry(line(a, 300), json("account_code", b));
            case "null account_code" -> entry(line(a, 300), json("account_code", null, "amount_cents", -300));
            case "amount_cents as a string" -> entry(line(a, 300), json("account_code", b, "amount_cents", "-300"));
            case "decimal amount_cents" -> entry(line(a, 300), line(b, new Raw("-300.0")));
            case "unknown top-level property" -> json("memo", "retry", "lines", lines);
            case "unknown line property" -> entry(line(a, 300), json("account_code", b, "amount_cents", -300, "memo", "x"));
            case "camelCase accountCode" -> entry(line(a, 300), json("accountCode", b, "amount_cents", -300));
            case "camelCase amountCents" -> entry(line(a, 300), json("account_code", b, "amountCents", -300));
            case "idempotency_key in the body" -> json("idempotency_key", key, "lines", lines);
            case "entry_id in the body" -> json("entry_id", first.jsonPath().getString("entry_id"), "lines", lines);
            case "the first response sent back as the body" -> first.asString();
            default -> throw new IllegalArgumentException(kind);
        };

        Response r = post(key, body);

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"text/plain", "application/xml"})
    @DisplayName("REQ: idempotency-check-order — an identical retry sent with a non-JSON Content-Type is 415 problem+json (default type), never replayed")
    void usedKeyWrongContentType(String contentType) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 300), line(b, -300));
        postOk(key, body);

        Response r = given().contentType(contentType).header(IDEMPOTENCY_KEY, key)
                .body(body.getBytes(StandardCharsets.UTF_8)).when().post(ENTRIES);

        assertProblem(r, 415);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertBalance(a, 300);
    }

    @Test
    @DisplayName("REQ: idempotency-check-order — an identical retry with no Content-Type header is 400 or 415 problem+json, never replayed")
    void usedKeyNoContentType() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 300), line(b, -300));
        postOk(key, body);

        RawResponse r = rawPost(null, keyHeader(latin1(key)), body);

        observe("used key + no Content-Type", r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isIn(400, 415);
        assertProblemBody(r.status(), r.contentType(), r.body());
        assertThat(r.headers()).as("headers").doesNotContainKey(REPLAYED.toLowerCase(Locale.ROOT));
        assertBalance(a, 300);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Idempotency_Key", "IdempotencyKey", "Idempotency-Keys", "X-Idempotency-Key"})
    @DisplayName("REQ: idempotency-check-order — an identical retry sending the used key under a different header name carries no Idempotency-Key: 400, never replayed")
    void usedKeyUnderWrongHeaderName(String headerName) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 300), line(b, -300));
        postOk(key, body);

        Response r = given().contentType("application/json").header(headerName, key).body(body).when().post(ENTRIES);

        assertProblem(r, 400);
        assertDefaultType(r);
        assertNotReplayed(r);
        assertBalance(a, 300);
    }

    // ------------------------------------------------------------------ helpers
    // (assertReplayOf, assertReplayed, assertNotReplayed, assertSameBody and assertReused live in
    // JournalSliceContractSupport, shared with the earlier-slice tests that repeat a key)

    /** Neither the stored codes (whole, or their unique tail) nor the stored amount appear in {@code text}. */
    static void assertRevealsNothingOf(String text, String where, String... storedCodes) {
        for (String code : storedCodes) {
            assertThat(text).as("%s must not reveal the stored account_code %s", where, code)
                    .doesNotContain(code)
                    .doesNotContain(code.substring(code.indexOf(':') + 1));
        }
        assertThat(text).as("%s must not reveal the stored amount", where).doesNotContain(String.valueOf(STORED));
    }

    /** Exactly one 201 without the replay header; every other response a 201 replay of it. */
    static void assertOneOriginalRestReplays(List<Response> rs, String what) {
        Response original = null;
        for (Response r : rs) {
            assertThat(r.statusCode()).as("%s: every identical post is 201 (body %s)", what, r.asString()).isEqualTo(201);
            if (r.headers().getValues(REPLAYED).isEmpty()) {
                assertThat(original).as("%s: only one response may lack %s", what, REPLAYED).isNull();
                original = r;
            }
        }
        assertThat(original).as("%s: exactly one response must be the original, without %s", what, REPLAYED).isNotNull();
        for (Response r : rs) {
            if (r != original) assertReplayOf(r, original);
        }
    }

    /** Exactly one original 201; every other response 422 idempotency-key-reused. Returns the winner's index. */
    int assertOneCreatedRestReused(List<Response> rs, String what) {
        int winner = -1;
        for (int i = 0; i < rs.size(); i++) {
            Response r = rs.get(i);
            if (r.statusCode() == 201) {
                assertThat(winner).as("%s: only one post may be created (second: %s)", what, r.asString()).isEqualTo(-1);
                assertNotReplayed(r);
                winner = i;
            } else {
                assertReused(r);
            }
        }
        assertThat(winner).as("%s: exactly one post must be created", what).isNotEqualTo(-1);
        return winner;
    }

    /** Posts every body at the same moment with one key; responses come back in submission order. */
    List<Response> postConcurrently(String key, List<String> bodies) throws Exception {
        int n = bodies.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (String body : bodies) {
                Callable<Response> task = () -> {
                    ready.countDown();
                    start.await();
                    return post(key, body);
                };
                futures.add(pool.submit(task));
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

    /** [1, -1, 2, -2, ..., n, -n]: balanced, non-zero, one line per account. */
    static List<BigInteger> pairs(int n) {
        List<BigInteger> amounts = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            amounts.add(BigInteger.valueOf(i));
            amounts.add(BigInteger.valueOf(-i));
        }
        return amounts;
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
