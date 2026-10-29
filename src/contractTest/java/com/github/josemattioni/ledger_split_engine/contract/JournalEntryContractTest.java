package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box contract for the journal-entries capability of the add-journal-entries slice,
 * written from openspec/changes/add-journal-entries/specs/journal-entries/spec.md and
 * PROJECT-CONVENTIONS.md only. Endpoint: {@code POST /api/v1/journal-entries}.
 *
 * <p>Tests whose scenario the spec leaves open accept every defensible outcome, still forbid
 * 500s and silent corruption, and print an {@code OBSERVED} line with what actually happened.
 *
 * <p>Scenarios superseded by append-only-ledger-and-entry-guards (an account repeated within an
 * entry, single-line entries, amounts beyond the permitted range) were updated to that spec;
 * its own scenarios live in JournalEntryGuardsContractTest.
 *
 * <p>Scenarios superseded by idempotent-replay (a repeated Idempotency-Key replays an identical
 * request and rejects a different one with 422, never 409; validation precedes the key check) were
 * updated to that spec; its own scenarios live in IdempotentReplayContractTest.
 *
 * <p>entry-reversal adds {@code reverses_entry_id} (null for an ordinary post) as the one identifier
 * besides {@code entry_id}; the identity test was updated to it. Its own scenarios live in
 * JournalEntryReversalContractTest.
 */
class JournalEntryContractTest extends JournalSliceContractSupport {

    // ------------------------------------------------------------------ post-balanced-journal-entry

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — balanced entry returns 201 JSON with entry_id, idempotency_key, created_at and lines")
    void postHappyPath() {
        String asset = newAccount("ASSET");
        String liability = newAccount("LIABILITY");
        String key = newKey();

        Response r = postOk(key, entry(line(asset, 1500), line(liability, -1500)));

        assertThat(r.contentType()).as("Content-Type").startsWith("application/json");
        Map<String, Object> body = r.jsonPath().getMap("");
        assertThat(body).containsKeys("entry_id", "idempotency_key", "created_at", "lines");
        assertThat(body.get("entry_id")).as("entry_id").isNotNull();
        assertThat(String.valueOf(body.get("entry_id"))).as("entry_id").isNotBlank();
        assertThat(body.get("idempotency_key")).as("idempotency_key must echo the header").isEqualTo(key);
        assertLines(r, List.of(asset, liability), cents(1500, -1500));
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — a successful post moves each account's balance by its line amount")
    void postIsReflectedInBalances() {
        String asset = newAccount("ASSET");
        String revenue = newAccount("REVENUE");
        postOk(entry(line(asset, 4200), line(revenue, -4200)));
        assertBalance(asset, 4200);
        assertBalance(revenue, -4200);
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — created_at is an ISO-8601 timestamp with offset, close to now")
    void createdAtIsSane() {
        Instant before = Instant.now().minus(2, ChronoUnit.MINUTES);
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String createdAt = postOk(entry(line(a, 1), line(b, -1))).jsonPath().getString("created_at");
        Instant after = Instant.now().plus(2, ChronoUnit.MINUTES);

        assertThat(createdAt).as("created_at").isNotBlank();
        try {
            assertThat(OffsetDateTime.parse(createdAt).toInstant()).isBetween(before, after);
        } catch (DateTimeParseException e) {
            fail("created_at '%s' is not an ISO-8601 timestamp with offset/Z", createdAt);
        }
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — response keys are snake_case at every level")
    void responseKeysSnakeCase() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r = postOk(entry(line(a, 10), line(b, -10)));
        assertSnakeCaseKeys(r.jsonPath().getMap(""), "entry response");
        List<Map<String, Object>> lines = r.jsonPath().getList("lines");
        for (Map<String, Object> l : lines) assertSnakeCaseKeys(l, "entry line");
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — amount_cents and line_number in the response are JSON integers, never floats or strings")
    void responseNumbersAreIntegers() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r = postOk(entry(line(a, 123456789L), line(b, -123456789L)));
        List<Map<String, Object>> lines = r.jsonPath().getList("lines");
        for (Map<String, Object> l : lines) {
            integer(l.get("amount_cents"), "amount_cents");
            integer(l.get("line_number"), "line_number");
        }
        assertThat(r.asString()).as("raw body must not render cents as decimals")
                .doesNotContainPattern("\"amount_cents\"\\s*:\\s*-?\\d+\\.")
                .doesNotContainPattern("\"amount_cents\"\\s*:\\s*\"");
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — one entry may span accounts of all four types")
    void allAccountTypesInOneEntry() {
        List<String> codes = newAccounts(4);
        List<BigInteger> amounts = cents(1000, -600, -300, -100);
        assertLines(postOk(entryOf(codes, amounts)), codes, amounts);
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — application/json with a charset parameter is accepted")
    void jsonWithCharsetAccepted() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r = given().contentType("application/json; charset=UTF-8").header(IDEMPOTENCY_KEY, newKey())
                .body(entry(line(a, 5), line(b, -5))).when().post(ENTRIES);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isEqualTo(201);
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — field order inside a line does not matter")
    void lineFieldOrderIrrelevant() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r = postOk(entry(json("amount_cents", 77, "account_code", a), json("amount_cents", -77, "account_code", b)));
        assertLines(r, List.of(a, b), cents(77, -77));
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — single zero-amount line (sums to zero; spec silent on zero lines) is 201 or 4xx problem+json, never 500")
    void singleZeroLine() {
        String a = newAccount("ASSET");
        Response r = post(newKey(), entry(line(a, 0)));
        observe("single zero-amount line", r);
        if (r.statusCode() == 201) {
            assertLines(r, List.of(a), cents(0));
        } else {
            assertProblemIn(r, 400, 422);
        }
        assertBalance(a, 0);
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — zero-amount line inside a balanced entry is 201 or 4xx problem+json, never 500")
    void zeroLineAmongOthers() {
        List<String> codes = newAccounts(3);
        List<BigInteger> amounts = cents(250, 0, -250);
        Response r = post(newKey(), entryOf(codes, amounts));
        observe("zero-amount line among others", r);
        if (r.statusCode() == 201) {
            assertLines(r, codes, amounts);
            assertBalance(codes.get(0), 250);
        } else {
            assertProblemIn(r, 400, 422);
            assertUntouched(codes);
        }
    }

    // ------------------------------------------------------------------ lines-numbered-by-submitted-order

    @Test
    @DisplayName("REQ: lines-numbered-by-submitted-order — three lines come back in submitted order numbered 1, 2, 3")
    void threeLinesNumbered() {
        List<String> codes = newAccounts(3);
        List<BigInteger> amounts = cents(300, -100, -200);
        assertLines(postOk(entryOf(codes, amounts)), codes, amounts);
    }

    @Test
    @DisplayName("REQ: lines-numbered-by-submitted-order — nine lines on nine distinct accounts keep submission order (not sorted by amount or account)")
    void nineLinesKeepOrder() {
        List<String> codes = newAccounts(9);
        List<BigInteger> amounts = cents(5, -1, 7, -3, 2, -9, 4, -6, 1);
        assertLines(postOk(entryOf(codes, amounts)), codes, amounts);
    }

    @Test
    @DisplayName("REQ: lines-numbered-by-submitted-order — 1000-line entry is numbered 1..1000 in order, or a 4xx problem+json; never 500")
    void thousandLines() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        List<String> codes = new ArrayList<>();
        List<BigInteger> amounts = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(i % 2 == 0 ? a : b);
            amounts.add(BigInteger.valueOf(i % 2 == 0 ? i + 1 : -i));
        }
        Response r = post(newKey(), entryOf(codes, amounts));
        observe("1000-line entry", r);
        if (r.statusCode() == 201) {
            assertLines(r, codes, amounts);
            assertBalance(a, 250_000);
            assertBalance(b, -250_000);
        } else {
            assertThat(r.statusCode()).as("body: %s", r.asString()).isBetween(400, 499);
            assertProblemShape(r);
            assertUntouched(a, b);
        }
    }

    // ------------------------------------------------------------------ journal-entry-must-balance

    @ParameterizedTest(name = "[{index}] amounts {0}")
    @ValueSource(strings = {"100,-99", "100,-101", "100,100", "-5,-5", "1,-1,1"})
    @DisplayName("REQ: journal-entry-must-balance — non-zero sum returns 422 unbalanced-entry problem+json describing the imbalance, nothing created")
    void unbalancedRejected(String csv) {
        // Single-line entries are reported as the two-line minimum (JournalEntryGuardsContractTest), not here.
        List<BigInteger> amounts = amounts(csv);
        List<String> codes = newAccounts(amounts.size());
        String detail = assertRule(post(newKey(), entryOf(codes, amounts)), UNBALANCED).toLowerCase(Locale.ROOT);
        // The spec fixes no wording: accept either naming the rule ("balance") or stating the non-zero sum.
        assertThat(detail.contains("balanc") || detail.contains("sum"))
                .as("detail must describe that the entry does not balance: %s", detail).isTrue();
        assertUntouched(codes);
    }

    @ParameterizedTest(name = "[{index}] amounts {0}")
    @ValueSource(strings = {"MAX,MAX,2", "MIN,MIN", "MAX,MAX,MAX,MAX,4", "MIN,MIN,MIN,MIN", "MAX,1", "MIN,-1"})
    @DisplayName("REQ: journal-entry-must-balance — sum that overflows 64-bit (incl. sums that wrap to exactly 0) returns 422 problem+json, nothing created")
    void overflowingSumRejected(String csv) {
        List<BigInteger> amounts = amounts(csv);
        List<String> codes = newAccounts(amounts.size());
        assertProblem(post(newKey(), entryOf(codes, amounts)), 422);
        assertUntouched(codes);
    }

    @Test
    @DisplayName("REQ: journal-entry-must-balance — [MAX, 1, MIN] (true sum 0, running sum overflows) is 201 with exact balances or 422; never 500")
    void runningSumOverflowsButTrueSumZero() {
        List<BigInteger> amounts = List.of(LONG_MAX, BigInteger.ONE, LONG_MIN);
        List<String> codes = newAccounts(3);
        Response r = post(newKey(), entryOf(codes, amounts));
        observe("[MAX, 1, MIN]", r);
        if (r.statusCode() == 201) {
            for (int i = 0; i < codes.size(); i++) assertBalance(codes.get(i), amounts.get(i));
        } else {
            assertProblem(r, 422);
            assertUntouched(codes);
        }
    }

    @Test
    @DisplayName("REQ: journal-entry-must-balance — entry that is both unbalanced and names an unknown account is 422, nothing created")
    void unbalancedAndUnknownAccount() {
        String a = newAccount("ASSET");
        assertProblem(post(newKey(), entry(line(a, 100), line(uniqueCode(), -1))), 422);
        assertUntouched(a);
    }

    // ------------------------------------------------------------------ lines-reference-existing-accounts

    @Test
    @DisplayName("REQ: lines-reference-existing-accounts — unknown account_code returns 422 problem+json naming it, nothing created")
    void unknownAccountRejected() {
        String a = newAccount("ASSET");
        String unknown = uniqueCode();
        String detail = assertProblem(post(newKey(), entry(line(a, 100), line(unknown, -100))), 422);
        assertThat(detail).as("detail must identify the unrecognized account_code").contains(unknown);
        assertUntouched(a);
    }

    @Test
    @DisplayName("REQ: lines-reference-existing-accounts — unknown account on the first line is rejected the same way")
    void unknownAccountFirstLine() {
        String a = newAccount("LIABILITY");
        String unknown = uniqueCode();
        String detail = assertProblem(post(newKey(), entry(line(unknown, 100), line(a, -100))), 422);
        assertThat(detail).as("detail must identify the unrecognized account_code").contains(unknown);
        assertUntouched(a);
    }

    @Test
    @DisplayName("REQ: lines-reference-existing-accounts — several unknown accounts: 422 naming at least one of them")
    void severalUnknownAccounts() {
        String a = newAccount("ASSET");
        String u1 = uniqueCode();
        String u2 = uniqueCode();
        String detail = assertProblem(post(newKey(), entry(line(u1, 100), line(a, 50), line(u2, -150))), 422);
        assertThat(detail.contains(u1) || detail.contains(u2))
                .as("detail must identify an unrecognized account_code: %s", detail).isTrue();
        assertUntouched(a);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"uppercased", "leading space", "trailing space", "empty", "whitespace", "malformed"})
    @DisplayName("REQ: lines-reference-existing-accounts — account_code that does not exactly match a registered code is 400/422 problem+json, never trimmed/case-folded")
    void inexactAccountCodeRejected(String variant) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String bad = switch (variant) {
            case "uppercased" -> b.toUpperCase(Locale.ROOT);
            case "leading space" -> " " + b;
            case "trailing space" -> b + " ";
            case "empty" -> "";
            case "whitespace" -> "   ";
            case "malformed" -> "Not A Code " + uniqueTail();
            default -> throw new IllegalArgumentException(variant);
        };
        Response r = post(newKey(), entry(line(a, 100), line(bad, -100)));
        observe("account_code variant: " + variant, r);
        assertProblemIn(r, 400, 422);
        assertUntouched(a, b);
    }

    // ------------------------------------------------------------------ idempotency-key-required

    @Test
    @DisplayName("REQ: idempotency-key-required — missing Idempotency-Key returns 400 problem+json naming the header, nothing created")
    void missingKeyRejected() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String detail = assertProblem(postWithoutKey(entry(line(a, 100), line(b, -100))), 400);
        assertThat(detail.toLowerCase(Locale.ROOT)).as("detail should name the Idempotency-Key header").contains("idempotency");
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: idempotency-key-required — missing key on an otherwise unbalanced entry is still rejected (400 or 422), nothing created")
    void missingKeyAndUnbalanced() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r = postWithoutKey(entry(line(a, 100), line(b, -1)));
        observe("missing key + unbalanced", r);
        assertProblemIn(r, 400, 422);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: idempotency-key-required — idempotency_key sent in the JSON body instead of the header returns 400, nothing created")
    void keyInBodyInsteadOfHeader() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        assertProblem(postWithoutKey(json("idempotency_key", newKey(), "lines", array(line(a, 1), line(b, -1)))), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("REQ: idempotency-key-required — empty or whitespace-only Idempotency-Key returns 400 problem+json, nothing created")
    void emptyKeyRejected(String value) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        RawResponse r = rawPostWithKey(latin1(value), entry(line(a, 100), line(b, -100)));
        assertRawProblem(r, 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {256, 257, 1000})
    @DisplayName("REQ: idempotency-key-required — Idempotency-Key longer than 255 characters returns 400 problem+json, nothing created")
    void tooLongKeyRejected(int length) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = padKey(length);
        assertProblem(post(key, entry(line(a, 100), line(b, -100))), 400);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: idempotency-key-required — Idempotency-Key of exactly 255 characters is accepted and echoed verbatim")
    void maxLengthKeyAccepted() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = padKey(255);
        Response r = postOk(key, entry(line(a, 100), line(b, -100)));
        assertThat(r.jsonPath().getString("idempotency_key")).isEqualTo(key);
    }

    @Test
    @DisplayName("REQ: idempotency-key-required — single-character Idempotency-Key is accepted: a free one posts 201, echoed, not a replay; one owned by an earlier run is skipped only on 422 idempotency-key-reused")
    void singleCharKeyAccepted() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        // Fresh accounts: no stored entry has these lines, so a used key is a reuse, never a replay.
        String body = entry(line(a, 100), line(b, -100));
        List<Character> chars = new ArrayList<>();
        for (char c = 0x21; c <= 0x7e; c++) chars.add(c);
        Collections.shuffle(chars);
        for (char c : chars) {
            RawResponse r = rawPostWithKey(new byte[] {(byte) c}, body);
            if (isKeyReused(r)) {
                // Keys never expire, so an earlier run against this database may own this one. Validation
                // precedes the key check, so even this answer shows the one-character key is well-formed.
                assertRawProblem(r, 422);
                continue;
            }
            assertThat(r.status()).as("key '%s': %s", c, r.body()).isEqualTo(201);
            assertThat(JsonPath.from(r.body()).getString("idempotency_key")).isEqualTo(String.valueOf(c));
            assertThat(r.headers()).as("first post with key '%s' is not a replay", c)
                    .doesNotContainKey(REPLAYED.toLowerCase(Locale.ROOT));
            assertBalance(a, 100);
            return;
        }
        fail("all 94 single-character keys are owned by earlier runs against this database (keys never expire); "
                + "run against a fresh database");
    }

    @Test
    @DisplayName("REQ: idempotency-key-required — key made of every visible ASCII character (0x21..0x7E) is accepted and echoed verbatim")
    void allVisibleAsciiKeyAccepted() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        StringBuilder key = new StringBuilder(uniqueTail());
        for (char c = 0x21; c <= 0x7e; c++) key.append(c);
        RawResponse r = rawPostWithKey(latin1(key.toString()), entry(line(a, 100), line(b, -100)));
        assertThat(r.status()).as("body: %s", r.body()).isEqualTo(201);
        assertThat(JsonPath.from(r.body()).getString("idempotency_key")).isEqualTo(key.toString());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"interior space", "interior tab", "UTF-8 e-acute", "Latin-1 e-acute byte"})
    @DisplayName("REQ: idempotency-key-required — Idempotency-Key with a non-visible-ASCII character the application receives returns 400 problem+json, nothing created")
    void keyWithInvalidCharacterRejected(String kind) {
        byte[] bad = switch (kind) {
            case "interior space" -> new byte[] {' '};
            case "interior tab" -> new byte[] {'\t'};
            case "UTF-8 e-acute" -> "é".getBytes(StandardCharsets.UTF_8);
            case "Latin-1 e-acute byte" -> new byte[] {(byte) 0xe9};
            default -> throw new IllegalArgumentException(kind);
        };
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        RawResponse r = rawPostWithKey(concat(latin1("key-"), bad, latin1(uniqueTail())), entry(line(a, 100), line(b, -100)));
        assertRawProblem(r, 400);
        assertUntouched(a, b);
    }

    /**
     * The carve-out in "Journal entry errors are reported as problem+json": the HTTP server rejects a control
     * character in a header before the application is involved, so the 400 is the server's own error page. The
     * status and "nothing created" are the contract; the body format is deliberately not asserted.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"DEL 0x7F", "control 0x01"})
    @DisplayName("REQ: idempotency-key-required — Idempotency-Key with a control character is rejected by the HTTP server before the application: 400 (the server's own page, not problem+json), nothing created")
    void keyWithControlCharacterRejectedByServer(String kind) {
        byte[] bad = switch (kind) {
            case "DEL 0x7F" -> new byte[] {0x7f};
            case "control 0x01" -> new byte[] {0x01};
            default -> throw new IllegalArgumentException(kind);
        };
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        RawResponse r = rawPostWithKey(concat(latin1("key-"), bad, latin1(uniqueTail())), entry(line(a, 100), line(b, -100)));
        assertThat(r.status()).as("status (Content-Type %s, body %s)", r.contentType(), r.body()).isEqualTo(400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"idempotency-key", "IDEMPOTENCY-KEY", "idempotency-KEY"})
    @DisplayName("REQ: idempotency-key-required — the header name is matched case-insensitively (HTTP semantics)")
    void headerNameCaseInsensitive(String headerName) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        Response r = given().contentType(ContentType.JSON).header(headerName, key)
                .body(entry(line(a, 100), line(b, -100))).when().post(ENTRIES);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isEqualTo(201);
        assertThat(r.jsonPath().getString("idempotency_key")).isEqualTo(key);
    }

    @Test
    @DisplayName("REQ: idempotency-key-required — two different Idempotency-Key headers: 400, or 201 keyed by exactly one of them; never a merged key")
    void repeatedKeyHeader() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String k1 = newKey();
        String k2 = newKey();
        RawResponse r = rawPost("application/json", concat(keyHeader(latin1(k1)), keyHeader(latin1(k2))),
                entry(line(a, 100), line(b, -100)));
        observe("two Idempotency-Key headers", r.status(), r.contentType(), r.body());
        if (r.status() == 201) {
            assertThat(JsonPath.from(r.body()).getString("idempotency_key"))
                    .as("entry must be keyed by one of the keys actually sent").isIn(k1, k2);
        } else {
            assertRawProblem(r, 400);
            assertUntouched(a, b);
        }
    }

    // ------------------------------------------------------------------ idempotency-keys-unique

    @Test
    @DisplayName("REQ: idempotency-keys-unique — reused key with different lines/accounts returns 422 idempotency-key-reused problem+json, never 409; original intact, nothing new created")
    void reusedKeyDifferentLines() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String d = newAccount("EXPENSE");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        assertReused(post(key, entry(line(c, 999), line(d, -999))));

        assertBalance(a, 300);
        assertBalance(b, -300);
        assertUntouched(c, d);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — identical repeat of a successful post is replayed: 201 with the identical body and Idempotent-Replayed: true, never 409; posted once")
    void identicalRepeatReplays() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 300), line(b, -300));
        Response first = postOk(key, body);

        assertReplayOf(post(key, body), first);
        assertBalance(a, 300);
        assertBalance(b, -300);
    }

    @Test
    @DisplayName("REQ: idempotency-check-order — reused key on an unbalanced body is 422 unbalanced-entry (validation precedes the key check), not idempotency-key-reused or 409; original intact")
    void reusedKeyUnbalancedBody() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        Response r = post(key, entry(line(c, 5), line(a, -1)));

        assertRule(r, UNBALANCED);
        assertNotReplayed(r);
        assertBalance(a, 300);
        assertUntouched(c);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — key of a post rejected with 422 was never used, so it can be posted with 201")
    void keyFromRejected422IsReusable() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        assertProblem(post(key, entry(line(a, 100), line(b, -99))), 422);
        postOk(key, entry(line(a, 100), line(b, -100)));
        assertBalance(a, 100);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — key of a post rejected with 400 (malformed body) can be posted with 201")
    void keyFromRejected400IsReusable() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        assertProblem(post(key, entry(line(a, 100), json("account_code", b))), 400);
        postOk(key, entry(line(a, 100), line(b, -100)));
        assertBalance(a, 100);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — keys are compared exactly: keys differing only in letter case are distinct")
    void keysAreCaseSensitive() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String lower = "case-" + uniqueTail();
        postOk(lower, entry(line(a, 10), line(b, -10)));
        Response r = post(lower.toUpperCase(Locale.ROOT), entry(line(a, 10), line(b, -10)));
        observe("upper-cased variant of a used key", r);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isEqualTo(201);
        assertBalance(a, 20);
    }

    @Test
    @DisplayName("REQ: idempotency-keys-unique — concurrent posts with one key and different lines: exactly one 201, the rest 422 idempotency-key-reused problem+json, never 409 or 500")
    void concurrentSameKey() throws Exception {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                long amount = i + 1;
                Callable<Response> task = () -> {
                    start.await();
                    return post(key, entry(line(a, amount), line(b, -amount)));
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            int created = 0;
            long winner = 0;
            for (int i = 0; i < n; i++) {
                Response r = futures.get(i).get();
                if (r.statusCode() == 201) {
                    assertNotReplayed(r);
                    created++;
                    winner = i + 1;
                } else {
                    assertReused(r);
                }
            }
            assertThat(created).as("exactly one concurrent post with the key wins").isEqualTo(1);
            assertBalance(a, winner);
            assertBalance(b, -winner);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ malformed-post-rejected

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{\"amount_cents\":-100}",                                   // missing account_code
            "{\"account_code\":\"%s\"}",                                  // missing amount_cents
            "{}",                                                          // empty line
            "{\"account_code\":null,\"amount_cents\":-100}",
            "{\"account_code\":\"%s\",\"amount_cents\":null}",
            "{\"account_code\":\"%s\",\"amount_cents\":\"-100\"}",        // numeric string, not coerced
            "{\"account_code\":\"%s\",\"amount_cents\":\"abc\"}",
            "{\"account_code\":\"%s\",\"amount_cents\":-100.9}",          // would balance if truncated
            "{\"account_code\":\"%s\",\"amount_cents\":-100.0}",          // decimal, even if integral
            "{\"account_code\":\"%s\",\"amount_cents\":-1E2}",            // exponent form
            "{\"account_code\":\"%s\",\"amount_cents\":-1e2}",
            "{\"account_code\":\"%s\",\"amount_cents\":true}",
            "{\"account_code\":\"%s\",\"amount_cents\":[-100]}",
            "{\"account_code\":\"%s\",\"amount_cents\":{\"v\":-100}}",
            "{\"account_code\":12345,\"amount_cents\":-100}",             // number, not coerced to string
            "{\"account_code\":true,\"amount_cents\":-100}",
            "{\"account_code\":[\"%s\"],\"amount_cents\":-100}",
            "{\"account_code\":{\"code\":\"%s\"},\"amount_cents\":-100}",
            "null",
            "-100",
            "\"%s\"",
            "[\"%s\",-100]",
    })
    @DisplayName("REQ: malformed-post-rejected — line missing a field, with a null, or with the wrong JSON type returns 400 problem+json, nothing created")
    void malformedLineRejected(String badLineTemplate) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String badLine = String.format(badLineTemplate, b);
        assertProblem(post(newKey(), entry(line(a, 100), badLine)), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "1000000000000000000000000000000"})
    @DisplayName("REQ: malformed-post-rejected — amount_cents outside signed 64-bit range returns 400 problem+json, nothing created")
    void outOfRangeAmountRejected(String amount) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        BigInteger v = new BigInteger(amount);
        assertProblem(post(newKey(), entry(line(a, v), line(b, v.negate()))), 400);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: malformed-post-rejected — amount_cents of exactly Long.MAX_VALUE is a well-formed integer (not 400): [MAX, -MAX] is 422 amount-limit-exceeded, nothing created")
    void maxAmountWellFormedButOverLimit() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        assertRule(post(newKey(), entry(line(a, LONG_MAX), line(b, LONG_MAX.negate()))), AMOUNT_LIMIT);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: malformed-post-rejected — amount_cents of exactly Long.MIN_VALUE is a well-formed integer (not 400): [MIN, MAX, 1] is 422 amount-limit-exceeded, nothing created")
    void minAmountWellFormedButOverLimit() {
        List<String> codes = newAccounts(3);
        assertRule(post(newKey(), entryOf(codes, List.of(LONG_MIN, LONG_MAX, BigInteger.ONE))), AMOUNT_LIMIT);
        assertUntouched(codes);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{}", "{\"lines\":null}", "{\"lines\":{}}", "{\"lines\":\"x\"}", "{\"lines\":1}",
            "{\"lines\":true}", "{\"lines\":[null]}", "{\"lines\":[[]]}"})
    @DisplayName("REQ: malformed-post-rejected — missing, null or non-array lines returns 400 problem+json")
    void malformedLinesRejected(String body) {
        assertProblem(post(newKey(), body), 400);
    }

    @Test
    @DisplayName("REQ: malformed-post-rejected — empty lines array (spec requires one or more lines) is rejected 400/422, never 201")
    void emptyLinesRejected() {
        Response r = post(newKey(), "{\"lines\":[]}");
        observe("empty lines array", r);
        assertProblemIn(r, 400, 422);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"memo", "description", "currency", "created_at", "metadata"})
    @DisplayName("REQ: malformed-post-rejected — unknown top-level property returns 400 problem+json, nothing created")
    void unknownTopLevelPropertyRejected(String field) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        assertProblem(post(newKey(), json(field, "x", "lines", array(line(a, 1), line(b, -1)))), 400);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: malformed-post-rejected — unknown property with a null value is still rejected, nothing created")
    void unknownNullPropertyRejected() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        assertProblem(post(newKey(), json("memo", null, "lines", array(line(a, 1), line(b, -1)))), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"description", "currency", "memo", "account_type", "side"})
    @DisplayName("REQ: malformed-post-rejected — unknown property inside a line returns 400 problem+json, nothing created")
    void unknownLinePropertyRejected(String field) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String body = entry(line(a, 1), json("account_code", b, "amount_cents", -1, field, "x"));
        assertProblem(post(newKey(), body), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"accountCode", "AccountCode", "account-code", "ACCOUNT_CODE", "accountcode"})
    @DisplayName("REQ: malformed-post-rejected — non-snake_case spelling of account_code is unrecognized (400), not an alias")
    void accountCodeVariantsRejected(String field) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String body = entry(line(a, 1), json(field, b, "amount_cents", -1));
        assertProblem(post(newKey(), body), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"amountCents", "AmountCents", "amount-cents", "AMOUNT_CENTS", "amount", "amountcents"})
    @DisplayName("REQ: malformed-post-rejected — non-snake_case spelling of amount_cents is unrecognized (400), not an alias")
    void amountCentsVariantsRejected(String field) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String body = entry(line(a, 1), json("account_code", b, field, -1));
        assertProblem(post(newKey(), body), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Lines", "LINES", "entryLines", "entry_lines", "line"})
    @DisplayName("REQ: malformed-post-rejected — misspelled/cased 'lines' key is unrecognized (400), not an alias")
    void linesVariantsRejected(String field) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        assertProblem(post(newKey(), json(field, array(line(a, 1), line(b, -1)))), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"line_number", "id", "entry_id", "currency", "created_at"})
    @DisplayName("REQ: malformed-post-rejected — server-assigned/response-only field sent inside a line returns 400, nothing created")
    void serverFieldInLineRejected(String field) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Object v = field.equals("line_number") || field.equals("id") ? (Object) 1 : "x";
        String body = entry(json(field, v, "account_code", a, "amount_cents", 1), line(b, -1));
        assertProblem(post(newKey(), body), 400);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: malformed-post-rejected — idempotency_key in the body alongside the header is an unrecognized field (400)")
    void keyInBodyAndHeader() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        assertProblem(post(key, json("idempotency_key", key, "lines", array(line(a, 1), line(b, -1)))), 400);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{",
            "{\"lines\":",
            "{\"lines\":[}",
            "{\"lines\":[{\"account_code\":\"a:b\",\"amount_cents\":1},]}",
            "{'lines':[]}",
            "{\"lines\":[{\"account_code\":\"a:b\" \"amount_cents\":1}]}",
            "not json at all",
            "{\"lines\":[]}}",
    })
    @DisplayName("REQ: malformed-post-rejected — malformed JSON returns 400 problem+json")
    void malformedJson(String body) {
        assertProblem(post(newKey(), body), 400);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"[]", "[{\"lines\":[]}]", "\"text\"", "42", "null", "true"})
    @DisplayName("REQ: malformed-post-rejected — non-object JSON body returns 400 problem+json")
    void nonObjectJson(String body) {
        assertProblem(post(newKey(), body), 400);
    }

    @Test
    @DisplayName("REQ: malformed-post-rejected — empty request body returns 400 problem+json")
    void emptyBody() {
        assertProblem(post(newKey(), ""), 400);
    }

    // ------------------------------------------------------------------ server-assigned-identity

    @Test
    @DisplayName("REQ: server-assigned-identity — each post gets a distinct entry_id; no id-like key but entry_id and reverses_entry_id (null for an ordinary post) appears at any level")
    void entryIdServerAssigned() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r1 = postOk(entry(line(a, 1), line(b, -1)));
        Response r2 = postOk(entry(line(a, 2), line(b, -2)));

        Object id1 = r1.jsonPath().get("entry_id");
        Object id2 = r2.jsonPath().get("entry_id");
        assertThat(id1).as("entry_id").isNotNull();
        assertThat(id2).as("entry_id").isNotNull();
        assertThat(String.valueOf(id1)).isNotBlank().isNotEqualTo(String.valueOf(id2));

        for (Response r : List.of(r1, r2)) {
            assertNoInternalId(r.jsonPath().getMap(""), Set.of("entry_id", "reverses_entry_id"), "entry response");
            assertThat(r.jsonPath().getMap("")).as("ordinary entry").containsEntry("reverses_entry_id", null);
            List<Map<String, Object>> lines = r.jsonPath().getList("lines");
            for (Map<String, Object> l : lines) assertNoInternalId(l, Set.of(), "entry line");
        }
    }

    @Test
    @DisplayName("REQ: server-assigned-identity — entry_id is not a sequential database key (consecutive posts are not n, n+1)")
    void entryIdNotSequential() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String id1 = String.valueOf(postOk(entry(line(a, 1), line(b, -1))).jsonPath().getString("entry_id"));
        String id2 = String.valueOf(postOk(entry(line(a, 1), line(b, -1))).jsonPath().getString("entry_id"));
        observe("entry_id pair", 201, "", id1 + " , " + id2);
        if (id1.matches("-?\\d{1,18}") && id2.matches("-?\\d{1,18}")) {
            long gap = Math.abs(Long.parseLong(id2) - Long.parseLong(id1));
            assertThat(gap).as("entry_ids %s then %s look like a database sequence", id1, id2).isGreaterThan(1000);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"entry_id", "id", "entryId", "journal_entry_id"})
    @DisplayName("REQ: server-assigned-identity — client-supplied entry identity in the request returns 400, nothing created")
    void clientEntryIdRejected(String field) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Object v = field.equals("id") ? (Object) 1 : "00000000-0000-0000-0000-000000000001";
        assertProblem(post(newKey(), json(field, v, "lines", array(line(a, 1), line(b, -1)))), 400);
        assertUntouched(a, b);
    }

    // ------------------------------------------------------------------ journal-errors-problem-json

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"text/plain", "application/xml", "text/json", "application/octet-stream"})
    @DisplayName("REQ: journal-errors-problem-json — non-JSON Content-Type returns 415 problem+json, nothing created")
    void wrongContentType(String contentType) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        byte[] body = entry(line(a, 1), line(b, -1)).getBytes(StandardCharsets.UTF_8);
        Response r = given().contentType(contentType).header(IDEMPOTENCY_KEY, newKey())
                .body(body).when().post(ENTRIES);
        assertProblem(r, 415);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: journal-errors-problem-json — form-encoded Content-Type returns 415 problem+json")
    void formContentType() {
        Response r = given().contentType("application/x-www-form-urlencoded").header(IDEMPOTENCY_KEY, newKey())
                .formParam("lines", "x").when().post(ENTRIES);
        assertProblem(r, 415);
    }

    @Test
    @DisplayName("REQ: journal-errors-problem-json — JSON body with no Content-Type header is 400/415 problem+json, nothing created")
    void noContentType() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        RawResponse r = rawPost(null, keyHeader(latin1(newKey())), entry(line(a, 1), line(b, -1)));
        observe("no Content-Type", r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isIn(400, 415);
        assertProblemBody(r.status(), r.contentType(), r.body());
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    @DisplayName("REQ: journal-errors-problem-json — unsupported method on the journal-entry collection returns 405 problem+json")
    void methodNotAllowed(String method) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r = given().contentType(ContentType.JSON).header(IDEMPOTENCY_KEY, newKey())
                .body(entry(line(a, 1), line(b, -1))).when().request(method, ENTRIES);
        assertProblem(r, 405);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: journal-errors-problem-json — GET on the journal-entry collection (no read endpoint in spec) is success or problem+json, never 500")
    void collectionGet() {
        Response r = given().when().get(ENTRIES);
        observe("GET /journal-entries", r);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isLessThan(500);
        if (r.statusCode() >= 400) assertProblemShape(r);
    }

    @Test
    @DisplayName("REQ: journal-errors-problem-json — Accept: application/xml on a rejected post still yields a problem body or 406, never an empty body")
    void errorWithXmlAccept() {
        Response r = given().accept("application/xml").contentType(ContentType.JSON)
                .body("{\"lines\":[]}").when().post(ENTRIES);
        observe("Accept xml on missing-key post", r);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isIn(400, 406, 422);
        assertThat(r.asString()).as("error must carry a body").isNotBlank();
    }

    // ------------------------------------------------------------------ helpers

    /** The exact answer for a key already used by a different request: 422 problem+json, type idempotency-key-reused. */
    private static boolean isKeyReused(RawResponse r) {
        return r.status() == 422 && r.contentType().startsWith(PROBLEM_JSON)
                && KEY_REUSED.equals(JsonPath.from(r.body()).getString("type"));
    }

    /** A unique visible-ASCII key of exactly {@code length} characters. */
    private String padKey(int length) {
        String base = newKey();
        return base + "k".repeat(length - base.length());
    }
}
