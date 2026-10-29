package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Black-box contract for the accounts-and-skeleton slice, written from
 * openspec/changes/accounts-and-skeleton/specs/account-registration/spec.md and
 * PROJECT-CONVENTIONS.md only.
 *
 * <p>Scenarios for the whole slice are grouped in one class for readability.
 *
 * <p>Assumed endpoints (the spec names none): {@code POST /api/v1/accounts} to register,
 * {@code GET /api/v1/accounts/{code}} to look up (the latter taken from SmokeContractTest).
 */
class AccountRegistrationContractTest extends ContractTestBase {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final List<String> ACCOUNT_TYPES = List.of("ASSET", "LIABILITY", "REVENUE", "EXPENSE");

    // ------------------------------------------------------------------ helpers

    /** The hex tail of {@link #uniqueCode()}, for building malformed-but-unique codes. */
    private String uniqueTail() {
        return uniqueCode().substring(uniqueCode().indexOf(':') + 1);
    }

    /** Marks a value to be embedded in JSON verbatim (numbers, arrays, objects). */
    private record Raw(String json) {}

    /** Builds a JSON object from alternating key/value pairs, preserving order. */
    private static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(quote((String) kv[i])).append(':').append(value(kv[i + 1]));
        }
        return sb.append('}').toString();
    }

    private static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof Raw raw) return raw.json();
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return quote(v.toString());
    }

    private static String quote(String s) {
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

    private Response register(String body) {
        return given().contentType(ContentType.JSON).body(body).when().post("/accounts");
    }

    private Response register(String code, String type) {
        return register(json("code", code, "type", type));
    }

    private Response registerOk(String body) {
        Response r = register(body);
        assertThat(r.statusCode())
                .as("registration of %s should be 201 but body was: %s", body, r.asString())
                .isEqualTo(201);
        return r;
    }

    private Response lookup(String code) {
        return given().when().get("/accounts/{code}", code);
    }

    private Response lookupOk(String code) {
        Response r = lookup(code);
        assertThat(r.statusCode())
                .as("lookup of %s should be 200 but body was: %s", code, r.asString())
                .isEqualTo(200);
        return r;
    }

    private void assertNotCreated(String code) {
        Response r = lookup(code);
        assertThat(r.statusCode())
                .as("account %s must not have been created, lookup returned: %s", code, r.asString())
                .isEqualTo(404);
    }

    /** Every error: expected status, problem+json content type, non-blank detail, no leaked internals. */
    private String assertProblem(Response r, int expectedStatus) {
        assertThat(r.statusCode())
                .as("status (body was: %s)", r.asString())
                .isEqualTo(expectedStatus);
        return assertProblemShape(r);
    }

    private String assertProblemShape(Response r) {
        assertThat(r.contentType())
                .as("Content-Type of %d error (body was: %s)", r.statusCode(), r.asString())
                .startsWith(PROBLEM_JSON);
        Map<String, Object> body = r.jsonPath().getMap("");
        assertThat(body).as("problem body").isNotNull();
        if (body.containsKey("status")) {
            assertThat(((Number) body.get("status")).intValue())
                    .as("problem 'status' member must match HTTP status")
                    .isEqualTo(r.statusCode());
        }
        Object detail = body.get("detail");
        assertThat(detail).as("problem 'detail' (body was: %s)", r.asString()).isInstanceOf(String.class);
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

    private static void assertNoInternalId(Map<String, Object> body) {
        for (String key : body.keySet()) {
            String k = key.toLowerCase(Locale.ROOT);
            boolean looksLikeId = k.equals("id") || k.equals("uuid") || k.equals("pk")
                    || k.endsWith("_id") || key.endsWith("Id");
            assertThat(looksLikeId)
                    .as("response must not expose an internal id, found key '%s' in %s", key, body)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ register-account

    @Test
    @DisplayName("REQ: register-account — valid code, type and owner_ref returns 201 with Location to the code")
    void registerHappyPath() {
        String code = uniqueCode();
        Response r = registerOk(json("code", code, "type", "ASSET", "owner_ref", "seller-42"));

        String location = r.header("Location");
        assertThat(location).as("Location header").isNotBlank();
        String path = URI.create(location).getRawPath();
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        assertThat(decoded).as("Location path").endsWith("/api/v1/accounts/" + code);
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(strings = {"ASSET", "LIABILITY", "REVENUE", "EXPENSE"})
    @DisplayName("REQ: register-account — each fixed account type is accepted and stored")
    void everyTypeAccepted(String type) {
        String code = uniqueCode();
        registerOk(json("code", code, "type", type));
        assertThat(lookupOk(code).jsonPath().getString("type")).isEqualTo(type);
    }

    @Test
    @DisplayName("REQ: register-account — Location header can be followed to retrieve the account")
    void locationIsFollowable() {
        String code = uniqueCode();
        String location = registerOk(json("code", code, "type", "LIABILITY")).header("Location");
        Response r = given().baseUri("http://localhost").basePath("").urlEncodingEnabled(false)
                .when().get(URI.create(location).isAbsolute() ? location : "http://localhost:" + portFrom() + location);
        assertThat(r.statusCode()).as("GET Location (%s) body: %s", location, r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getString("code")).isEqualTo(code);
    }

    private int portFrom() {
        return io.restassured.RestAssured.port;
    }

    @Test
    @DisplayName("REQ: register-account — omitted owner_ref creates account with no owner reference")
    void ownerRefOmitted() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "REVENUE"));
        assertThat(lookupOk(code).jsonPath().getString("owner_ref")).isNull();
    }

    @Test
    @DisplayName("REQ: register-account — explicit null owner_ref creates account with no owner reference")
    void ownerRefExplicitNull() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "EXPENSE", "owner_ref", null));
        assertThat(lookupOk(code).jsonPath().getString("owner_ref")).isNull();
    }

    @Test
    @DisplayName("REQ: look-up-account — owner_ref key is present (as null) when the account has no owner")
    void ownerRefKeyPresentWhenNull() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "EXPENSE"));
        Map<String, Object> body = lookupOk(code).jsonPath().getMap("");
        assertThat(body).as("lookup body lists owner_ref even when null").containsKey("owner_ref");
        assertThat(body.get("owner_ref")).isNull();
    }

    @Test
    @DisplayName("REQ: register-account — application/json with charset parameter is accepted")
    void jsonWithCharsetAccepted() {
        String code = uniqueCode();
        Response r = given().contentType("application/json; charset=UTF-8")
                .body(json("code", code, "type", "ASSET")).when().post("/accounts");
        assertThat(r.statusCode()).as("body: %s", r.asString()).isEqualTo(201);
    }

    @Test
    @DisplayName("REQ: register-account — field order in the request body does not matter")
    void fieldOrderIrrelevant() {
        String code = uniqueCode();
        registerOk(json("owner_ref", "o-1", "type", "ASSET", "code", code));
        Response r = lookupOk(code);
        assertThat(r.jsonPath().getString("owner_ref")).isEqualTo("o-1");
    }

    // ------------------------------------------------------------------ account-code-uniqueness

    @Test
    @DisplayName("REQ: account-code-uniqueness — duplicate code returns 409 problem+json and leaves the original intact")
    void duplicateCode() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "ASSET", "owner_ref", "first"));

        Response dup = register(json("code", code, "type", "LIABILITY", "owner_ref", "second"));
        String detail = assertProblem(dup, 409);
        assertThat(detail).as("409 detail should identify the conflicting code").contains(code);

        Response original = lookupOk(code);
        assertThat(original.jsonPath().getString("type")).isEqualTo("ASSET");
        assertThat(original.jsonPath().getString("owner_ref")).isEqualTo("first");
    }

    @Test
    @DisplayName("REQ: account-code-uniqueness — identical duplicate request is still 409, not an idempotent 200/201")
    void identicalDuplicateStillConflicts() {
        String code = uniqueCode();
        String body = json("code", code, "type", "ASSET");
        registerOk(body);
        assertProblem(register(body), 409);
    }

    @Test
    @DisplayName("REQ: account-code-uniqueness — concurrent registrations of one code yield exactly one 201, rest 409, no 500")
    void concurrentDuplicates() throws Exception {
        String code = uniqueCode();
        String body = json("code", code, "type", "ASSET");
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                Callable<Response> task = () -> {
                    start.await();
                    return register(body);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            int created = 0;
            for (Future<Response> f : futures) {
                Response r = f.get();
                if (r.statusCode() == 201) {
                    created++;
                } else {
                    assertProblem(r, 409);
                }
            }
            assertThat(created).as("exactly one concurrent registration wins").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ account-code-format

    @Test
    @DisplayName("REQ: account-code-format — prefix-only snake_case code (like marketplace_commission) is accepted")
    void prefixOnlyCodeAccepted() {
        String code = "marketplace_commission_" + uniqueTail();
        registerOk(json("code", code, "type", "REVENUE"));
        assertThat(lookupOk(code).jsonPath().getString("code")).isEqualTo(code);
    }

    @Test
    @DisplayName("REQ: account-code-format — code with numeric :identifier suffix (like seller_payable:123) is accepted")
    void numericSuffixAccepted() {
        String code = "seller_payable_" + uniqueTail() + ":123";
        registerOk(json("code", code, "type", "LIABILITY"));
        assertThat(lookupOk(code).jsonPath().getString("code")).isEqualTo(code);
    }

    @Test
    @DisplayName("REQ: account-code-format — code like acquirer_fee (single underscore, no suffix) is accepted")
    void acquirerFeeShapeAccepted() {
        String code = "acquirer_fee" + uniqueTail().replaceAll("[0-9]", "");
        registerOk(json("code", code, "type", "EXPENSE"));
    }

    @Test
    @DisplayName("REQ: account-code-format — single-letter code is accepted")
    void singleLetterPrefixShape() {
        // uniqueness forces a suffix; the prefix itself is a single letter
        String code = "a:" + uniqueTail();
        registerOk(json("code", code, "type", "ASSET"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "UPPER_%s",          // uppercase letters
            "mixed_Case_%s",     // uppercase in middle
            "has space_%s",      // interior space
            "9starts_digit_%s",  // leading digit
            " leading_ws_%s",    // leading whitespace
            "trailing_ws_%s ",   // trailing whitespace
            "trailing_nl_%s\n",  // trailing newline (catches regex '$' before final line terminator)
            "hyphen-prefix_%s",  // hyphen in prefix
            "dot.prefix_%s",     // dot
            "empty_suffix_%s:",  // colon with empty identifier
            "two_colons_%s:a:b", // more than one suffix
            ":leading_colon_%s", // no prefix
            "_leading_us_%s",    // prefix starts with underscore
            "tab\tinside_%s",    // tab
            "café_%s",      // non-ascii lowercase letter
    })
    @DisplayName("REQ: account-code-format — malformed code returns 400 problem+json describing the shape, not created")
    void malformedCodeRejected(String template) {
        String code = String.format(template, uniqueTail());
        Response r = register(code, "ASSET");
        String detail = assertProblem(r, 400);
        assertThat(detail.toLowerCase(Locale.ROOT)).as("detail must describe the code rule").contains("code");
        if (!code.contains("\n") && !code.contains("\t")) {
            assertNotCreated(code);
        }
    }

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("REQ: account-code-format — empty or whitespace-only code returns 400 problem+json")
    void blankCodeRejected(String code) {
        assertProblem(register(code, "ASSET"), 400);
    }

    @Test
    @DisplayName("REQ: register-account — missing code returns 400 problem+json")
    void missingCode() {
        assertProblem(register(json("type", "ASSET")), 400);
    }

    @Test
    @DisplayName("REQ: register-account — null code returns 400 problem+json")
    void nullCode() {
        assertProblem(register(json("code", null, "type", "ASSET")), 400);
    }

    @Test
    @DisplayName("REQ: account-code-format — numeric JSON code (not a string) returns 400 problem+json")
    void numericCode() {
        assertProblem(register(json("code", new Raw("12345"), "type", "ASSET")), 400);
    }

    @Test
    @DisplayName("REQ: account-code-format — object/array JSON code returns 400 problem+json")
    void structuredCode() {
        assertProblem(register(json("code", new Raw("{\"a\":1}"), "type", "ASSET")), 400);
        assertProblem(register(json("code", new Raw("[\"a\"]"), "type", "ASSET")), 400);
    }

    // ------------------------------------------------------------------ account-type-validation

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @ValueSource(strings = {"BOGUS", "asset", "Asset", " ASSET", "ASSET ", "", "   ", "EQUITY", "ASSETS"})
    @DisplayName("REQ: account-type-validation — invalid type returns 400 problem+json listing allowed values, not created")
    void invalidTypeRejected(String type) {
        String code = uniqueCode();
        String detail = assertProblem(register(code, type), 400);
        for (String allowed : ACCOUNT_TYPES) {
            assertThat(detail).as("detail must list allowed values").contains(allowed);
        }
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: account-type-validation — numeric type (enum ordinal) is rejected with 400, not coerced")
    void ordinalTypeRejected() {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", new Raw("0"))), 400);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: account-type-validation — missing type returns 400 problem+json, not created")
    void missingType() {
        String code = uniqueCode();
        assertProblem(register(json("code", code)), 400);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: account-type-validation — null type returns 400 problem+json, not created")
    void nullType() {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", null)), 400);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: account-type-validation — array/object type returns 400 problem+json, not created")
    void structuredType() {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", new Raw("[\"ASSET\"]"))), 400);
        assertProblem(register(json("code", code, "type", new Raw("{\"v\":\"ASSET\"}"))), 400);
        assertNotCreated(code);
    }

    // ------------------------------------------------------------------ owner-reference-validation

    @ParameterizedTest(name = "[{index}] blank ''{0}''")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\n "})
    @DisplayName("REQ: owner-reference-validation — empty/whitespace-only owner_ref returns 400 naming owner_ref, not created")
    void blankOwnerRefRejected(String ownerRef) {
        String code = uniqueCode();
        String detail = assertProblem(register(json("code", code, "type", "ASSET", "owner_ref", ownerRef)), 400);
        assertThat(detail).as("detail must name owner_ref").contains("owner_ref");
        assertNotCreated(code);
    }

    @ParameterizedTest(name = "[{index}] padded ''{0}''")
    @ValueSource(strings = {" 123 ", " 123", "123 ", "123\n", "\t123", "123\t", " 123"})
    @DisplayName("REQ: owner-reference-validation — whitespace-padded owner_ref returns 400 (not trimmed), not created")
    void paddedOwnerRefRejected(String ownerRef) {
        String code = uniqueCode();
        String detail = assertProblem(register(json("code", code, "type", "ASSET", "owner_ref", ownerRef)), 400);
        assertThat(detail).as("detail must name owner_ref").contains("owner_ref");
        assertThat(detail.toLowerCase(Locale.ROOT)).as("detail must mention whitespace").contains("whitespace");
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: owner-reference-validation — interior whitespace is opaque and stored exactly")
    void interiorWhitespaceOwnerRefAccepted() {
        String code = uniqueCode();
        String ownerRef = "acme  seller\t42";
        registerOk(json("code", code, "type", "ASSET", "owner_ref", ownerRef));
        assertThat(lookupOk(code).jsonPath().getString("owner_ref")).isEqualTo(ownerRef);
    }

    @Test
    @DisplayName("REQ: owner-reference-validation — opaque non-ASCII owner_ref round-trips unchanged")
    void unicodeOwnerRefRoundTrips() {
        String code = uniqueCode();
        String ownerRef = "ñandú-客户-🦊/42?x=1&y=\"q\"";
        registerOk(json("code", code, "type", "LIABILITY", "owner_ref", ownerRef));
        assertThat(lookupOk(code).jsonPath().getString("owner_ref")).isEqualTo(ownerRef);
    }

    @Test
    @DisplayName("REQ: owner-reference-validation — single-character owner_ref is accepted")
    void singleCharOwnerRef() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "ASSET", "owner_ref", "x"));
        assertThat(lookupOk(code).jsonPath().getString("owner_ref")).isEqualTo("x");
    }

    @Test
    @DisplayName("REQ: owner-reference-validation — numeric JSON owner_ref is rejected with 400, not coerced to a string")
    void numericOwnerRefRejected() {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", "ASSET", "owner_ref", new Raw("123"))), 400);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: owner-reference-validation — object/array owner_ref returns 400, not created")
    void structuredOwnerRefRejected() {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", "ASSET", "owner_ref", new Raw("{\"id\":1}"))), 400);
        assertProblem(register(json("code", code, "type", "ASSET", "owner_ref", new Raw("[\"a\"]"))), 400);
        assertNotCreated(code);
    }

    // ------------------------------------------------------------------ fixed-settlement-currency

    @Test
    @DisplayName("REQ: fixed-settlement-currency — every account gets the same non-blank currency regardless of type")
    void currencyAlwaysAssigned() {
        String a = uniqueCode();
        String b = uniqueCode();
        registerOk(json("code", a, "type", "ASSET"));
        registerOk(json("code", b, "type", "EXPENSE", "owner_ref", "z"));
        String ca = lookupOk(a).jsonPath().getString("currency");
        String cb = lookupOk(b).jsonPath().getString("currency");
        assertThat(ca).as("currency").isNotBlank().matches("[A-Z]{3}");
        assertThat(cb).isEqualTo(ca);
    }

    @Test
    @DisplayName("REQ: fixed-settlement-currency — a client-supplied currency is never stored")
    void clientCurrencyNotStored() {
        // Reference account to learn the engine currency.
        String ref = uniqueCode();
        registerOk(json("code", ref, "type", "ASSET"));
        String engineCurrency = lookupOk(ref).jsonPath().getString("currency");

        String code = uniqueCode();
        Response r = register(json("code", code, "type", "ASSET", "currency", "XXX"));
        if (r.statusCode() == 201) {
            assertThat(lookupOk(code).jsonPath().getString("currency")).isEqualTo(engineCurrency);
        } else {
            // Spec is ambiguous: "currency" may be treated as an unrecognized request field.
            assertProblem(r, 400);
            assertNotCreated(code);
        }
    }

    // ------------------------------------------------------------------ look-up-account

    @Test
    @DisplayName("REQ: look-up-account — existing account returns 200 JSON with code, type, owner_ref, currency, created_at")
    void lookupExisting() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "LIABILITY", "owner_ref", "merchant-7"));
        Response r = lookupOk(code);
        assertThat(r.contentType()).startsWith("application/json");
        Map<String, Object> body = r.jsonPath().getMap("");
        assertThat(body).containsKeys("code", "type", "owner_ref", "currency", "created_at");
        assertThat(body.get("code")).isEqualTo(code);
        assertThat(body.get("type")).isEqualTo("LIABILITY");
        assertThat(body.get("owner_ref")).isEqualTo("merchant-7");
    }

    @Test
    @DisplayName("REQ: look-up-account — lookup response uses snake_case only (no camelCase keys)")
    void lookupKeysAreSnakeCase() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "ASSET", "owner_ref", "o"));
        Map<String, Object> body = lookupOk(code).jsonPath().getMap("");
        for (String key : body.keySet()) {
            assertThat(key).as("response key").matches("[a-z][a-z0-9_]*");
        }
    }

    @Test
    @DisplayName("REQ: look-up-account — registration timestamp is ISO-8601 with offset and close to now")
    void createdAtIsSaneTimestamp() {
        Instant before = Instant.now().minus(2, ChronoUnit.MINUTES);
        String code = uniqueCode();
        registerOk(json("code", code, "type", "ASSET"));
        String createdAt = lookupOk(code).jsonPath().getString("created_at");
        Instant after = Instant.now().plus(2, ChronoUnit.MINUTES);

        assertThat(createdAt).as("created_at").isNotBlank();
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(createdAt).toInstant();
        } catch (DateTimeParseException e) {
            fail("created_at '%s' is not an ISO-8601 timestamp with offset/Z", createdAt);
            return;
        }
        assertThat(parsed).isBetween(before, after);
    }

    @Test
    @DisplayName("REQ: look-up-account — unknown code returns 404 problem+json whose detail names the code")
    void lookupUnknown() {
        String code = uniqueCode();
        String detail = assertProblem(lookup(code), 404);
        assertThat(detail).as("404 detail should say no account exists for that code").contains(code);
    }

    @Test
    @DisplayName("REQ: look-up-account — code lookup is exact (uppercased variant of a registered code is not a match)")
    void lookupIsCaseSensitive() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "ASSET"));
        Response r = lookup(code.toUpperCase(Locale.ROOT));
        assertThat(r.statusCode()).as("body: %s", r.asString()).isBetween(400, 499);
        assertProblemShape(r);
    }

    @Test
    @DisplayName("REQ: look-up-account — lookup with a malformed code is a 4xx problem+json, never 200 or 500")
    void lookupMalformedCode() {
        Response r = lookup("Not A Valid Code");
        assertThat(r.statusCode()).as("body: %s", r.asString()).isBetween(400, 499);
        assertProblemShape(r);
    }

    // ------------------------------------------------------------------ internal-identity-not-exposed

    @Test
    @DisplayName("REQ: internal-identity-not-exposed — lookup body contains no internal id")
    void lookupHasNoId() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "ASSET"));
        assertNoInternalId(lookupOk(code).jsonPath().getMap(""));
    }

    @Test
    @DisplayName("REQ: internal-identity-not-exposed — registration response body (if any) and Location reference only the code")
    void registrationHasNoId() {
        String code = uniqueCode();
        Response r = registerOk(json("code", code, "type", "ASSET"));
        String raw = r.asString();
        if (raw != null && !raw.isBlank()) {
            assertNoInternalId(r.jsonPath().getMap(""));
        }
        String path = URLDecoder.decode(URI.create(r.header("Location")).getRawPath(), StandardCharsets.UTF_8);
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        assertThat(lastSegment).isEqualTo(code);
        assertThat(URI.create(r.header("Location")).getRawQuery()).as("Location must not carry a query").isNull();
    }

    @Test
    @DisplayName("REQ: internal-identity-not-exposed — client cannot supply an 'id' field (400, not created)")
    void clientIdRejected() {
        String code = uniqueCode();
        assertProblem(register(json("id", 1, "code", code, "type", "ASSET")), 400);
        assertNotCreated(code);
    }

    // ------------------------------------------------------------------ request-field-names

    @Test
    @DisplayName("REQ: request-field-names — unknown extra property returns 400 problem+json naming it, not created")
    void unknownPropertyRejected() {
        String code = uniqueCode();
        String detail = assertProblem(register(json("code", code, "type", "ASSET", "nickname", "x")), 400);
        assertThat(detail).as("detail should name the unrecognized field").contains("nickname");
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: request-field-names — unknown property with null value is still rejected")
    void unknownNullPropertyRejected() {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", "ASSET", "nickname", null)), 400);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: request-field-names — camelCase ownerRef is an unrecognized field (400), not an alias")
    void camelCaseOwnerRefRejected() {
        String code = uniqueCode();
        String detail = assertProblem(register(json("code", code, "type", "ASSET", "ownerRef", "o-1")), 400);
        assertThat(detail).as("detail should name the unrecognized field").contains("ownerRef");
        assertNotCreated(code);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Owner_Ref", "OWNER_REF", "owner-ref", "ownerref", "OwnerRef"})
    @DisplayName("REQ: request-field-names — differently-cased/spelled owner_ref variants are rejected with 400, not created")
    void ownerRefVariantsRejected(String fieldName) {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", "ASSET", fieldName, "o-1")), 400);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: request-field-names — capitalised 'Code' / 'TYPE' keys are not accepted as aliases")
    void capitalisedCoreFieldsRejected() {
        String code = uniqueCode();
        assertProblem(register(json("Code", code, "type", "ASSET")), 400);
        assertProblem(register(json("code", code, "TYPE", "ASSET")), 400);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: request-field-names — snake_case response field sent back in request (created_at) is rejected")
    void readOnlyFieldRejected() {
        String code = uniqueCode();
        assertProblem(register(json("code", code, "type", "ASSET", "created_at", "2020-01-01T00:00:00Z")), 400);
        assertNotCreated(code);
    }

    // ------------------------------------------------------------------ errors-as-problem-json

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{",
            "{\"code\":",
            "{\"code\":\"a:b\",\"type\":\"ASSET\",}",
            "{'code':'a:b','type':'ASSET'}",
            "{\"code\":\"a:b\" \"type\":\"ASSET\"}",
            "not json at all",
            "{\"code\":\"a:b\",\"type\":\"ASSET\"}}",
    })
    @DisplayName("REQ: errors-as-problem-json — malformed JSON returns 400 problem+json")
    void malformedJson(String body) {
        assertProblem(register(body), 400);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"[]", "[{\"code\":\"a:b\",\"type\":\"ASSET\"}]", "\"text\"", "42", "null", "true"})
    @DisplayName("REQ: errors-as-problem-json — non-object JSON body returns 400 problem+json")
    void nonObjectJson(String body) {
        assertProblem(register(body), 400);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — empty request body returns 400 problem+json")
    void emptyBody() {
        assertProblem(register(""), 400);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — empty JSON object returns 400 problem+json")
    void emptyObject() {
        assertProblem(register("{}"), 400);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — text/plain Content-Type returns 415 problem+json, not created")
    void textPlainContentType() {
        String code = uniqueCode();
        Response r = given().contentType("text/plain")
                .body(json("code", code, "type", "ASSET")).when().post("/accounts");
        assertProblem(r, 415);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — application/xml Content-Type returns 415 problem+json, not created")
    void xmlContentType() {
        String code = uniqueCode();
        Response r = given().contentType("application/xml")
                .body("<account><code>" + code + "</code><type>ASSET</type></account>").when().post("/accounts");
        assertProblem(r, 415);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — form-encoded Content-Type returns 415 problem+json, not created")
    void formContentType() {
        String code = uniqueCode();
        Response r = given().contentType("application/x-www-form-urlencoded")
                .formParam("code", code).formParam("type", "ASSET").when().post("/accounts");
        assertProblem(r, 415);
        assertNotCreated(code);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — framework-default 404 for an unmapped path is problem+json")
    void unmappedPath404() {
        assertProblem(given().when().get("/no-such-resource-" + uniqueTail()), 404);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — unsupported method on an account returns 405 problem+json")
    void methodNotAllowed() {
        String code = uniqueCode();
        registerOk(json("code", code, "type", "ASSET"));
        assertProblem(given().when().delete("/accounts/{code}", code), 405);
        lookupOk(code);
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — GET on the account collection is either success or problem+json")
    void collectionGet() {
        Response r = given().when().get("/accounts");
        assertThat(r.statusCode()).as("body: %s", r.asString()).isLessThan(500);
        if (r.statusCode() >= 400) {
            assertProblemShape(r);
        }
    }

    @Test
    @DisplayName("REQ: errors-as-problem-json — Accept: application/xml on an error still yields problem+json or 406, never a bare body")
    void errorWithXmlAccept() {
        Response r = given().accept("application/xml").when().get("/accounts/{code}", uniqueCode());
        assertThat(r.statusCode()).as("body: %s", r.asString()).isIn(404, 406);
        assertThat(r.asString()).as("error must carry a body").isNotBlank();
    }

    // ------------------------------------------------------------------ boundary lengths (spec silent on limits)

    @ParameterizedTest(name = "code length {0}")
    @ValueSource(ints = {64, 100, 128, 255, 256, 1000, 10_000})
    @DisplayName("REQ: account-code-format — long codes are either accepted and retrievable verbatim, or 400 problem+json; never 500")
    void longCodes(int length) {
        String prefix = uniqueCode() + "_";
        String code = prefix + "a".repeat(Math.max(0, length - prefix.length()));
        assertThat(code).hasSize(Math.max(length, prefix.length()));
        Response r = register(code, "ASSET");
        if (r.statusCode() == 201) {
            if (length <= 1000) {
                assertThat(lookupOk(code).jsonPath().getString("code"))
                        .as("accepted code must round-trip without truncation").isEqualTo(code);
            }
        } else {
            assertProblem(r, 400);
        }
    }

    @ParameterizedTest(name = "owner_ref length {0}")
    @ValueSource(ints = {64, 255, 256, 1000, 10_000, 100_000})
    @DisplayName("REQ: owner-reference-validation — long owner_ref is either stored verbatim or 400 problem+json; never 500")
    void longOwnerRefs(int length) {
        String code = uniqueCode();
        String ownerRef = "o".repeat(length);
        Response r = register(json("code", code, "type", "ASSET", "owner_ref", ownerRef));
        if (r.statusCode() == 201) {
            assertThat(lookupOk(code).jsonPath().getString("owner_ref"))
                    .as("accepted owner_ref must round-trip without truncation").isEqualTo(ownerRef);
        } else {
            assertProblem(r, 400);
            assertNotCreated(code);
        }
    }
}
