package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for the add-journal-entries slice, written from
 * openspec/changes/add-journal-entries/specs/ and PROJECT-CONVENTIONS.md only.
 *
 * <p>The spec names no paths. These were found by probing over HTTP (unmapped paths answer a
 * 404 problem; these do not): {@code POST /api/v1/journal-entries} and
 * {@code GET /api/v1/accounts/{code}/balance}. Accounts are registered through
 * {@code POST /api/v1/accounts}, as in the accounts-and-skeleton suite.
 *
 * <p>"Creates nothing" is observed through the balance endpoint: every account used by a
 * rejected post is fresh, so it must still read zero.
 *
 * <p>The ledger-rule problem types and the permitted amount range come from
 * openspec/changes/append-only-ledger-and-entry-guards/specs/journal-entries/spec.md, which
 * names the range but not its bounds; {@link #permittedLimit()} finds them over HTTP.
 */
abstract class JournalSliceContractSupport extends ContractTestBase {

    static final String PROBLEM_JSON = "application/problem+json";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String ENTRIES = "/journal-entries";
    static final String BALANCE = "/accounts/{code}/balance";
    static final List<String> ACCOUNT_TYPES = List.of("ASSET", "LIABILITY", "REVENUE", "EXPENSE");

    static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    static final String UNBALANCED = "urn:ledger-split-engine:problem:unbalanced-entry";
    static final String DEGENERATE = "urn:ledger-split-engine:problem:degenerate-entry";
    static final String UNKNOWN_ACCOUNT = "urn:ledger-split-engine:problem:unknown-account";
    static final String AMOUNT_LIMIT = "urn:ledger-split-engine:problem:amount-limit-exceeded";
    /** From openspec/changes/idempotent-replay/specs/journal-entries/spec.md. */
    static final String KEY_REUSED = "urn:ledger-split-engine:problem:idempotency-key-reused";
    /** Response header marking a replay, per the same spec. */
    static final String REPLAYED = "Idempotent-Replayed";
    /** From openspec/changes/archive/2026-10-15-harden-request-validation/specs/journal-entries/spec.md. */
    static final String INVALID_ACCOUNT_CODE = "urn:ledger-split-engine:problem:invalid-account-code";
    /** From openspec/changes/archive/2026-10-21-entry-reversal/specs/journal-entries/spec.md. */
    static final String CANNOT_REVERSE_REVERSAL = "urn:ledger-split-engine:problem:cannot-reverse-a-reversal";
    static final String ALREADY_REVERSED = "urn:ledger-split-engine:problem:entry-already-reversed";

    /** Largest x for which [x, -x] posts 201; found once per JVM by {@link #permittedLimit()}. */
    private static BigInteger permittedLimit;

    // ------------------------------------------------------------------ JSON building

    /** Marks a value to be embedded in JSON verbatim (numbers, arrays, objects, odd tokens). */
    record Raw(String json) {}

    /** Builds a JSON object from alternating key/value pairs, preserving order. */
    static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(quote((String) kv[i])).append(':').append(value(kv[i + 1]));
        }
        return sb.append('}').toString();
    }

    static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof Raw raw) return raw.json();
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return quote(v.toString());
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static Raw array(String... elements) {
        return new Raw("[" + String.join(",", elements) + "]");
    }

    static String line(String accountCode, Object amountCents) {
        return json("account_code", accountCode, "amount_cents", amountCents);
    }

    static String entry(String... lines) {
        return json("lines", array(lines));
    }

    static String entryOf(List<String> codes, List<BigInteger> amounts) {
        String[] lines = new String[codes.size()];
        for (int i = 0; i < lines.length; i++) lines[i] = line(codes.get(i), amounts.get(i));
        return entry(lines);
    }

    static List<BigInteger> cents(long... values) {
        return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }

    /** Parses {@code "100,-99,MAX,MIN"} into exact amounts (MAX/MIN are the signed 64-bit limits). */
    static List<BigInteger> amounts(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).map(t -> switch (t) {
            case "MAX" -> LONG_MAX;
            case "MIN" -> LONG_MIN;
            default -> new BigInteger(t);
        }).toList();
    }

    // ------------------------------------------------------------------ accounts and keys

    /** The hex tail of {@link #uniqueCode()}, for building unique values of other shapes. */
    String uniqueTail() {
        String code = uniqueCode();
        return code.substring(code.indexOf(':') + 1);
    }

    /** Registers a fresh account (a precondition, not under test) and returns its code. */
    String newAccount(String type) {
        String code = uniqueCode();
        Response r = given().contentType(ContentType.JSON).body(json("code", code, "type", type))
                .when().post("/accounts");
        assertThat(r.statusCode())
                .as("precondition: registering a %s account should be 201, body: %s", type, r.asString())
                .isEqualTo(201);
        return code;
    }

    /** Fresh accounts, cycling through every account type. */
    List<String> newAccounts(int n) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) codes.add(newAccount(ACCOUNT_TYPES.get(i % ACCOUNT_TYPES.size())));
        return codes;
    }

    /** A fresh, well-formed idempotency key (46 visible ASCII characters). */
    String newKey() {
        return uniqueCode();
    }

    // ------------------------------------------------------------------ posting

    Response post(String key, String body) {
        return given().contentType(ContentType.JSON).header(IDEMPOTENCY_KEY, key).body(body)
                .when().post(ENTRIES);
    }

    Response postWithoutKey(String body) {
        return given().contentType(ContentType.JSON).body(body).when().post(ENTRIES);
    }

    Response postOk(String key, String body) {
        Response r = post(key, body);
        assertThat(r.statusCode())
                .as("post should be 201 but was %d (%s): %s", r.statusCode(), r.contentType(), r.asString())
                .isEqualTo(201);
        return r;
    }

    Response postOk(String body) {
        return postOk(newKey(), body);
    }

    /** Asserts the response's lines, in order, against the submitted codes and amounts. */
    static void assertLines(Response r, List<String> codes, List<BigInteger> amounts) {
        List<Map<String, Object>> lines = r.jsonPath().getList("lines");
        assertThat(lines).as("lines (body %s)", r.asString()).isNotNull().hasSize(codes.size());
        for (int i = 0; i < codes.size(); i++) {
            Map<String, Object> l = lines.get(i);
            assertThat(l).as("line at position %d", i).containsKeys("line_number", "account_code", "amount_cents");
            assertThat(integer(l.get("line_number"), "line_number"))
                    .as("line_number of the line at submitted position %d", i)
                    .isEqualTo(BigInteger.valueOf(i + 1));
            assertThat(l.get("account_code")).as("account_code of line %d", i + 1).isEqualTo(codes.get(i));
            assertThat(integer(l.get("amount_cents"), "amount_cents"))
                    .as("amount_cents of line %d", i + 1)
                    .isEqualTo(amounts.get(i));
        }
    }

    // ------------------------------------------------------------------ balances

    Response balance(String code) {
        return given().when().get(BALANCE, code);
    }

    /** Reads only {@code balance_cents}, so one wrong field name elsewhere does not cascade. */
    BigInteger balanceOf(String code) {
        Response r = balance(code);
        assertThat(r.statusCode())
                .as("balance of %s should be 200 but body was: %s", code, r.asString())
                .isEqualTo(200);
        return integer(r.jsonPath().get("balance_cents"), "balance_cents of " + code + " (body " + r.asString() + ")");
    }

    void assertBalance(String code, BigInteger expected) {
        assertThat(balanceOf(code)).as("balance of %s", code).isEqualTo(expected);
    }

    void assertBalance(String code, long expected) {
        assertBalance(code, BigInteger.valueOf(expected));
    }

    /** "Creates nothing": every (fresh) account a rejected post touched still reads zero. */
    void assertUntouched(Collection<String> codes) {
        for (String code : codes) {
            assertThat(balanceOf(code))
                    .as("a rejected post must create nothing, but %s has a non-zero balance", code)
                    .isZero();
        }
    }

    void assertUntouched(String... codes) {
        assertUntouched(List.of(codes));
    }

    // ------------------------------------------------------------------ JSON values

    /** The value must be a JSON integer (not a float, decimal, string or null); returns it exactly. */
    static BigInteger integer(Object v, String what) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return BigInteger.valueOf(((Number) v).longValue());
        }
        if (v instanceof BigInteger b) return b;
        throw new AssertionError(what + " must be a JSON integer, but was " + v + " ("
                + (v == null ? "null" : v.getClass().getSimpleName()) + ")");
    }

    static void assertSnakeCaseKeys(Map<String, ?> body, String where) {
        for (String key : body.keySet()) {
            assertThat(key).as("key in %s", where).matches("[a-z][a-z0-9_]*");
        }
    }

    /** No key that looks like an internal database id, except the ones the spec names. */
    static void assertNoInternalId(Map<String, ?> body, Set<String> allowed, String where) {
        for (String key : body.keySet()) {
            if (allowed.contains(key)) continue;
            String k = key.toLowerCase(Locale.ROOT);
            boolean looksLikeId = k.equals("id") || k.equals("uuid") || k.equals("pk")
                    || k.endsWith("_id") || key.endsWith("Id");
            assertThat(looksLikeId)
                    .as("%s must not expose an internal id, found key '%s' in %s", where, key, body)
                    .isFalse();
        }
    }

    /** Prints what a tolerant (spec-ambiguous) test actually observed, for the QA report. */
    static void observe(String what, int status, String contentType, String body) {
        String b = body == null ? "" : body.replaceAll("\\s+", " ");
        System.out.println("OBSERVED " + what + " -> " + status + " " + contentType + " "
                + (b.length() > 300 ? b.substring(0, 300) + "..." : b));
    }

    static void observe(String what, Response r) {
        observe(what, r.statusCode(), r.contentType(), r.asString());
    }

    // ------------------------------------------------------------------ problem+json

    /** Every error: expected status, problem+json content type, non-blank detail, no leaked internals. */
    String assertProblem(Response r, int expectedStatus) {
        assertThat(r.statusCode())
                .as("status (Content-Type %s, body %s)", r.contentType(), r.asString())
                .isEqualTo(expectedStatus);
        return assertProblemShape(r);
    }

    /** For scenarios where the spec is silent between several error statuses. */
    String assertProblemIn(Response r, Integer... allowed) {
        assertThat(r.statusCode())
                .as("status (Content-Type %s, body %s)", r.contentType(), r.asString())
                .isIn(Arrays.asList(allowed));
        return assertProblemShape(r);
    }

    String assertProblemShape(Response r) {
        return assertProblemBody(r.statusCode(), r.contentType(), r.asString());
    }

    static String assertProblemBody(int status, String contentType, String body) {
        assertThat(contentType)
                .as("Content-Type of %d error (body was: %s)", status, body)
                .startsWith(PROBLEM_JSON);
        Map<String, Object> map;
        try {
            map = JsonPath.from(body).getMap("");
        } catch (RuntimeException e) {
            throw new AssertionError("problem body of " + status + " is not a JSON object: " + body, e);
        }
        assertThat(map).as("problem body").isNotNull();
        if (map.containsKey("status")) {
            assertThat(((Number) map.get("status")).intValue())
                    .as("problem 'status' member must match HTTP status")
                    .isEqualTo(status);
        }
        Object detail = map.get("detail");
        assertThat(detail).as("problem 'detail' (body was: %s)", body).isInstanceOf(String.class);
        String d = (String) detail;
        assertThat(d.isBlank()).as("problem 'detail' must not be blank").isFalse();
        assertThat(d)
                .as("detail must not leak internal class/package names")
                .doesNotContain("com.github.josemattioni")
                .doesNotContain("org.springframework")
                .doesNotContain("tools.jackson")
                .doesNotContain("com.fasterxml")
                .doesNotContain("org.postgresql")
                .doesNotContain("org.hibernate")
                .doesNotContain("Exception");
        return d;
    }

    /** A 422 problem+json carrying the given ledger-rule type; returns its detail. */
    String assertRule(Response r, String type) {
        String detail = assertProblem(r, 422);
        assertThat(typeOf(r)).as("problem type (body %s)", r.asString()).isEqualTo(type);
        return detail;
    }

    static String typeOf(Response r) {
        return r.jsonPath().getString("type");
    }

    /** Non-422 errors keep the default problem type: absent (RFC 9457 reads that as about:blank) or about:blank. */
    static void assertDefaultType(Response r) {
        String type = typeOf(r);
        assertThat(type == null || type.equals("about:blank"))
                .as("a %d keeps the default problem type, but type was '%s' (body %s)", r.statusCode(), type, r.asString())
                .isTrue();
    }

    // ------------------------------------------------------------------ idempotent replay

    /** The retry replays {@code original}: 201, exactly one {@code Idempotent-Replayed: true}, and the identical body. */
    static void assertReplayOf(Response retry, Response original) {
        assertReplayed(retry);
        assertSameBody(retry, original);
    }

    static void assertReplayed(Response r) {
        assertThat(r.statusCode())
                .as("an identical retry must be 201 (Content-Type %s, body %s)", r.contentType(), r.asString())
                .isEqualTo(201);
        assertThat(r.headers().getValues(REPLAYED))
                .as("a replay carries exactly one '%s: true' header (headers: %s)", REPLAYED, r.headers())
                .containsExactly("true");
    }

    static void assertNotReplayed(Response r) {
        assertThat(r.headers().getValues(REPLAYED))
                .as("a %d that is not a replay carries no %s header (headers: %s)", r.statusCode(), REPLAYED, r.headers())
                .isEmpty();
    }

    /** "Exactly as first returned": member by member for a readable failure, then the whole body. */
    static void assertSameBody(Response replay, Response original) {
        JsonPath o = original.jsonPath();
        JsonPath p = replay.jsonPath();
        for (String member : List.of("entry_id", "idempotency_key", "created_at")) {
            assertThat(String.valueOf(p.<Object>get(member)))
                    .as("replayed %s must be exactly as first returned", member)
                    .isEqualTo(String.valueOf(o.<Object>get(member)));
        }
        assertThat(p.<Object>get("lines")).as("replayed lines").isEqualTo(o.get("lines"));
        assertThat(p.getMap(""))
                .as("replayed body must equal the first body%n first:  %s%n replay: %s", original.asString(), replay.asString())
                .isEqualTo(o.getMap(""));
    }

    /** A 422 idempotency-key-reused that is not marked as a replay; returns its detail. */
    String assertReused(Response r) {
        String detail = assertRule(r, KEY_REUSED);
        assertNotReplayed(r);
        return detail;
    }

    // ------------------------------------------------------------------ permitted amount range

    /** Largest x for which [x, -x] on fresh accounts posts 201, by bisection over (1, Long.MAX_VALUE). */
    BigInteger permittedLimit() {
        if (permittedLimit != null) return permittedLimit;
        Response atMax = postPair(LONG_MAX);
        assertThat(atMax.statusCode())
                .as("the spec implies a permitted range narrower than 64 bits, but [MAX, -MAX] was accepted: %s", atMax.asString())
                .isNotEqualTo(201);
        assertRule(atMax, AMOUNT_LIMIT);
        BigInteger accepted = BigInteger.ONE;
        BigInteger rejected = LONG_MAX;
        while (rejected.subtract(accepted).compareTo(BigInteger.ONE) > 0) {
            BigInteger mid = accepted.add(rejected).shiftRight(1);
            Response r = postPair(mid);
            if (r.statusCode() == 201) {
                accepted = mid;
            } else {
                assertRule(r, AMOUNT_LIMIT);
                rejected = mid;
            }
        }
        observe("permitted |amount_cents| limit (largest x with [x, -x] accepted)", 201, "", accepted.toString());
        permittedLimit = accepted;
        return accepted;
    }

    private Response postPair(BigInteger x) {
        return post(newKey(), entry(line(newAccount("ASSET"), x), line(newAccount("LIABILITY"), x.negate())));
    }

    // ------------------------------------------------------------------ raw HTTP

    /**
     * A hand-written HTTP/1.1 exchange, for header bytes an HTTP client library would refuse,
     * re-encode or merge (empty values, control characters, non-ASCII bytes, repeated headers).
     */
    record RawResponse(int status, Map<String, String> headers, String body) {
        String contentType() {
            return headers.getOrDefault("content-type", "");
        }
    }

    static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    static byte[] keyHeader(byte[] value) {
        return concat(latin1(IDEMPOTENCY_KEY + ": "), value, latin1("\r\n"));
    }

    RawResponse rawPostWithKey(byte[] keyValue, String body) {
        return rawPost("application/json", keyHeader(keyValue), body);
    }

    /** POSTs to the journal-entry endpoint. {@code contentType} null sends no Content-Type at all. */
    RawResponse rawPost(String contentType, byte[] extraHeaderLines, String body) {
        return rawPostTo(ENTRIES, contentType, extraHeaderLines, body);
    }

    /**
     * POSTs to {@code path} under /api/v1, written verbatim (percent-encode it yourself).
     * {@code contentType} null sends no Content-Type; {@code body} null sends no body and no Content-Length.
     */
    RawResponse rawPostTo(String path, String contentType, byte[] extraHeaderLines, String body) {
        byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder()
                .append("POST /api/v1").append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost:").append(RestAssured.port).append("\r\n")
                .append("Accept: */*\r\n")
                .append("Connection: close\r\n");
        if (body != null) head.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
        return rawExchange(concat(latin1(head.toString()), extraHeaderLines, latin1("\r\n"), bodyBytes));
    }

    /** Sends {@code request} byte for byte on a fresh connection (it must ask to close it) and parses the reply. */
    RawResponse rawExchange(byte[] request) {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            return parseRaw(socket.getInputStream().readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String assertRawProblem(RawResponse r, int expectedStatus) {
        assertThat(r.status())
                .as("status (Content-Type %s, body %s)", r.contentType(), r.body())
                .isEqualTo(expectedStatus);
        return assertProblemBody(r.status(), r.contentType(), r.body());
    }

    private static RawResponse parseRaw(byte[] resp) {
        int sep = indexOf(resp, latin1("\r\n\r\n"), 0);
        if (sep < 0) {
            throw new AssertionError("not an HTTP response: " + new String(resp, StandardCharsets.ISO_8859_1));
        }
        String[] head = new String(resp, 0, sep, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(head[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < head.length; i++) {
            int colon = head[i].indexOf(':');
            if (colon > 0) {
                headers.put(head[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        head[i].substring(colon + 1).trim());
            }
        }
        byte[] body = Arrays.copyOfRange(resp, sep + 4, resp.length);
        if ("chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))) {
            body = dechunk(body);
        }
        return new RawResponse(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < in.length) {
            int eol = indexOf(in, latin1("\r\n"), pos);
            if (eol < 0) break;
            String sizeLine = new String(in, pos, eol - pos, StandardCharsets.ISO_8859_1);
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
            if (size == 0) break;
            out.write(in, eol + 2, size);
            pos = eol + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
