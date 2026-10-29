package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box contract for the journal-entries delta of the append-only-ledger-and-entry-guards
 * slice, written from
 * openspec/changes/append-only-ledger-and-entry-guards/specs/journal-entries/spec.md and
 * PROJECT-CONVENTIONS.md only. Endpoint: {@code POST /api/v1/journal-entries}.
 *
 * <p>Every negative fixture starts from a fully valid entry (two or more non-zero lines on
 * distinct fresh accounts, balanced, one fresh Idempotency-Key) and breaks exactly one thing.
 * Fixtures that cannot avoid a second defect are named {@code precedence...}: where the spec or
 * the conventions say which rule wins they assert it; otherwise they accept either rule and print
 * an {@code OBSERVED} line.
 *
 * <p>"Creates nothing" is observed two ways: every registered account the rejected post named
 * still reads zero, and the rejected post's Idempotency-Key is still free for a valid post.
 */
class JournalEntryGuardsContractTest extends JournalSliceContractSupport {

    // ------------------------------------------------------------------ post-balanced-journal-entry

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — three non-zero lines on distinct accounts post 201 JSON with entry_id, idempotency_key, created_at and lines in order")
    void distinctAccountsHappyPath() {
        List<String> codes = newAccounts(3);
        List<BigInteger> amounts = cents(250, -200, -50);
        String key = newKey();

        Response r = postOk(key, entryOf(codes, amounts));

        assertThat(r.contentType()).as("Content-Type").startsWith("application/json");
        Map<String, Object> body = r.jsonPath().getMap("");
        assertThat(body).containsKeys("entry_id", "idempotency_key", "created_at", "lines");
        assertThat(String.valueOf(body.get("entry_id"))).as("entry_id").isNotBlank().isNotEqualTo("null");
        assertThat(body.get("idempotency_key")).as("idempotency_key must echo the header").isEqualTo(key);
        assertThat(String.valueOf(body.get("created_at"))).as("created_at").isNotBlank().isNotEqualTo("null");
        assertLines(r, codes, amounts);
        for (int i = 0; i < codes.size(); i++) assertBalance(codes.get(i), amounts.get(i));
    }

    @Test
    @DisplayName("REQ: post-balanced-journal-entry — 200 lines on 200 distinct accounts post 201 numbered 1..200, or a 4xx problem+json; never 500")
    void twoHundredDistinctLines() {
        List<String> codes = newAccounts(200);
        List<BigInteger> amounts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            amounts.add(BigInteger.valueOf(i + 1));
            amounts.add(BigInteger.valueOf(-(i + 1)));
        }
        Response r = post(newKey(), entryOf(codes, amounts));
        observe("200-line entry on distinct accounts", r);
        if (r.statusCode() == 201) {
            assertLines(r, codes, amounts);
            assertBalance(codes.get(198), 100);
            assertBalance(codes.get(199), -100);
        } else {
            assertThat(r.statusCode()).as("body: %s", r.asString()).isBetween(400, 499);
            assertProblemShape(r);
            assertUntouched(codes);
        }
    }

    // ------------------------------------------------------------------ journal-entry-needs-two-lines

    @Test
    @DisplayName("REQ: journal-entry-needs-two-lines — empty lines array returns 422 degenerate-entry stating the two-line minimum; its key stays unused")
    void emptyLinesRejected() {
        String key = newKey();
        String detail = assertRule(post(key, "{\"lines\":[]}"), DEGENERATE);
        assertStatesTwoLineMinimum(detail);
        assertKeyStillFree(key);
    }

    @ParameterizedTest(name = "[{index}] single line of {0}")
    @ValueSource(strings = {"100", "-1", "0", "MAX"})
    @DisplayName("REQ: journal-entry-needs-two-lines — precedence: a single line, whatever its amount, is 422 degenerate-entry reporting the two-line minimum (not an imbalance), nothing created")
    void precedenceSingleLine(String amount) {
        String a = newAccount("ASSET");
        String key = newKey();
        String detail = assertRule(post(key, entryOf(List.of(a), amounts(amount))), DEGENERATE);
        assertStatesTwoLineMinimum(detail);
        assertUntouched(a);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: journal-entry-needs-two-lines — exactly two non-zero balanced lines on distinct accounts are accepted (201)")
    void twoLinesAccepted() {
        String a = newAccount("EXPENSE");
        String b = newAccount("REVENUE");
        assertLines(postOk(entry(line(a, 999), line(b, -999))), List.of(a, b), cents(999, -999));
        assertBalance(a, 999);
        assertBalance(b, -999);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{}", "{\"lines\":null}"})
    @DisplayName("REQ: journal-entry-needs-two-lines — missing or null lines stays a malformed request (400, default type), distinct from an empty lines array (422)")
    void absentLinesStay400(String body) {
        Response r = post(newKey(), body);
        assertProblem(r, 400);
        assertDefaultType(r);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{\"lines\":[]", "{\"lines\":[]]}", "{\"lines\":[],}", "{lines:[]}"})
    @DisplayName("REQ: journal-entry-needs-two-lines — malformed JSON around an empty lines array is 400 (default type), not the two-line 422")
    void malformedJsonAroundEmptyLines(String body) {
        Response r = post(newKey(), body);
        assertProblem(r, 400);
        assertDefaultType(r);
    }

    @Test
    @DisplayName("REQ: journal-entry-needs-two-lines — precedence (spec silent): empty lines with no Idempotency-Key is 400 or 422 problem+json")
    void precedenceEmptyLinesWithoutKey() {
        Response r = postWithoutKey("{\"lines\":[]}");
        observe("empty lines + missing Idempotency-Key", r);
        assertProblemIn(r, 400, 422);
        if (r.statusCode() == 422) assertThat(typeOf(r)).as("problem type").isEqualTo(DEGENERATE);
    }

    // ------------------------------------------------------------------ line-amounts-not-zero

    @Test
    @DisplayName("REQ: line-amounts-not-zero — lines 250, 0, -250 on three distinct accounts return 422 degenerate-entry identifying the zero-amount line, nothing created")
    void zeroLineRejected() {
        List<String> codes = newAccounts(3);
        String key = newKey();
        String detail = assertRule(post(key, entryOf(codes, cents(250, 0, -250))), DEGENERATE);
        assertIdentifiesLine(detail, codes.get(1), 2);
        assertUntouched(codes);
        assertKeyStillFree(key);
    }

    @ParameterizedTest(name = "[{index}] zero on line {0}")
    @ValueSource(ints = {1, 3})
    @DisplayName("REQ: line-amounts-not-zero — a zero-amount first or last line is 422 degenerate-entry identifying that line, nothing created")
    void zeroLineAtEitherEnd(int lineNumber) {
        List<String> codes = newAccounts(3);
        List<BigInteger> amounts = lineNumber == 1 ? cents(0, 250, -250) : cents(250, -250, 0);
        String detail = assertRule(post(newKey(), entryOf(codes, amounts)), DEGENERATE);
        assertIdentifiesLine(detail, codes.get(lineNumber - 1), lineNumber);
        assertUntouched(codes);
    }

    @Test
    @DisplayName("REQ: line-amounts-not-zero — every line zero ([0, 0] on distinct accounts, sums to zero) is 422 degenerate-entry, not accepted as balanced")
    void allLinesZero() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String detail = assertRule(post(newKey(), entry(line(a, 0), line(b, 0))), DEGENERATE);
        assertMentionsZero(detail);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: line-amounts-not-zero — JSON -0 is a zero amount: 422 degenerate-entry identifying the line, nothing created")
    void negativeZero() {
        List<String> codes = newAccounts(3);
        String body = entry(line(codes.get(0), 250), line(codes.get(1), new Raw("-0")), line(codes.get(2), -250));
        String detail = assertRule(post(newKey(), body), DEGENERATE);
        assertIdentifiesLine(detail, codes.get(1), 2);
        assertUntouched(codes);
    }

    @Test
    @DisplayName("REQ: line-amounts-not-zero — +1 / -1 (the nearest amounts to zero) are accepted")
    void nearestNonZeroAccepted() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        assertLines(postOk(entry(line(a, 1), line(b, -1))), List.of(a, b), cents(1, -1));
        assertBalance(a, 1);
        assertBalance(b, -1);
    }

    @ParameterizedTest(name = "[{index}] amount_cents {0}")
    @ValueSource(strings = {"0.0", "-0.0", "0e0", "0E0", "00", "-00", "\"0\""})
    @DisplayName("REQ: line-amounts-not-zero — precedence: zero spelled as a decimal, exponent, leading-zero or string is a malformed amount (400, default type), not a zero-amount 422")
    void precedenceMalformedZero(String spelled) {
        List<String> codes = newAccounts(3);
        String body = entry(line(codes.get(0), 250), line(codes.get(1), new Raw(spelled)), line(codes.get(2), -250));
        Response r = post(newKey(), body);
        assertProblem(r, 400);
        assertDefaultType(r);
        assertUntouched(codes);
    }

    @ParameterizedTest(name = "[{index}] amount_cents {0}")
    @ValueSource(strings = {"missing", "null"})
    @DisplayName("REQ: line-amounts-not-zero — a missing or null amount_cents is a malformed line (400, default type), never defaulted to zero (422)")
    void absentAmountIsNotZero(String kind) {
        List<String> codes = newAccounts(3);
        String bad = kind.equals("missing")
                ? json("account_code", codes.get(1))
                : json("account_code", codes.get(1), "amount_cents", null);
        Response r = post(newKey(), entry(line(codes.get(0), 250), bad, line(codes.get(2), -250)));
        assertProblem(r, 400);
        assertDefaultType(r);
        assertUntouched(codes);
    }

    @Test
    @DisplayName("REQ: line-amounts-not-zero — camelCase amountCents: 0 is an unrecognized field (400, default type), not read as a zero amount (422)")
    void camelCaseZeroAmount() {
        List<String> codes = newAccounts(3);
        String bad = json("account_code", codes.get(1), "amountCents", 0);
        Response r = post(newKey(), entry(line(codes.get(0), 250), bad, line(codes.get(2), -250)));
        assertProblem(r, 400);
        assertDefaultType(r);
        assertUntouched(codes);
    }

    @Test
    @DisplayName("REQ: line-amounts-not-zero — a line repeating the amount_cents key (0, then -250) is never 500; outcome observed (spec silent on duplicate JSON keys)")
    void duplicateAmountKey() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String dup = "{\"account_code\":" + quote(b) + ",\"amount_cents\":0,\"amount_cents\":-250}";
        Response r = post(newKey(), entry(line(a, 250), dup));
        observe("duplicate amount_cents key in one line (0 then -250)", r);
        if (r.statusCode() == 201) {
            assertLines(r, List.of(a, b), cents(250, -250));
        } else {
            assertProblemIn(r, 400, 422);
            assertUntouched(a, b);
        }
    }

    // ------------------------------------------------------------------ account-at-most-once-per-entry

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — 100 and 50 on one account with -150 on another return 422 degenerate-entry naming the repeated account_code, nothing created")
    void repeatedAcrossSeveralLines() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String detail = assertRule(post(key, entry(line(a, 100), line(a, 50), line(b, -150))), DEGENERATE);
        assertThat(detail).as("detail must identify the repeated account_code").contains(a);
        assertUntouched(a, b);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — 700 and -700 on the same account return 422 degenerate-entry rather than posting a net-zero entry")
    void debitAndCreditSameAccount() {
        String a = newAccount("ASSET");
        String key = newKey();
        String detail = assertRule(post(key, entry(line(a, 700), line(a, -700))), DEGENERATE);
        assertThat(detail).as("detail must identify the repeated account_code").contains(a);
        assertUntouched(a);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — a repeat on non-adjacent lines (1 and 3) is 422 degenerate-entry naming the account")
    void repeatedNonAdjacent() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String detail = assertRule(post(newKey(), entry(line(a, 100), line(b, -150), line(a, 50))), DEGENERATE);
        assertThat(detail).as("detail must identify the repeated account_code").contains(a);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — repeated lines that cancel each other out (100, -100) beside a valid pair are still 422 degenerate-entry")
    void repeatedLinesThatCancel() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String body = entry(line(a, 100), line(a, -100), line(b, 50), line(c, -50));
        String detail = assertRule(post(newKey(), body), DEGENERATE);
        assertThat(detail).as("detail must identify the repeated account_code").contains(a);
        assertUntouched(a, b, c);
    }

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — an account on three lines is 422 degenerate-entry naming it")
    void accountOnThreeLines() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String detail = assertRule(post(newKey(), entry(line(a, 1), line(a, 1), line(a, 1), line(b, -3))), DEGENERATE);
        assertThat(detail).as("detail must identify the repeated account_code").contains(a);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — two different accounts each repeated is 422 degenerate-entry naming at least one of them")
    void twoAccountsEachRepeated() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String detail = assertRule(post(newKey(), entry(line(a, 1), line(a, 1), line(b, -1), line(b, -1))), DEGENERATE);
        assertThat(detail.contains(a) || detail.contains(b))
                .as("detail must identify a repeated account_code: %s", detail).isTrue();
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — camelCase accountCode naming the first line's account is an unrecognized field (400, default type), not read as a repeat (422)")
    void camelCaseRepeatedAccount() {
        String a = newAccount("ASSET");
        Response r = post(newKey(), entry(line(a, 100), json("accountCode", a, "amount_cents", -100)));
        assertProblem(r, 400);
        assertDefaultType(r);
        assertUntouched(a);
    }

    @Test
    @DisplayName("REQ: account-at-most-once-per-entry — a null account_code on two lines is a malformed line (400, default type), not a repeated account")
    void nullCodeOnTwoLines() {
        String body = entry(json("account_code", null, "amount_cents", 100), json("account_code", null, "amount_cents", -100));
        Response r = post(newKey(), body);
        assertProblem(r, 400);
        assertDefaultType(r);
    }

    @ParameterizedTest(name = "[{index}] account_code ''{0}'' on both lines")
    @ValueSource(strings = {"", "   "})
    @DisplayName("REQ: account-at-most-once-per-entry — the same empty or whitespace-only account_code on two lines is 400/422 problem+json (never 201/500); which rule fires is observed")
    void blankCodeOnTwoLines(String code) {
        Response r = post(newKey(), entry(line(code, 100), line(code, -100)));
        observe("account_code '" + code + "' on both lines", r);
        assertProblemIn(r, 400, 422);
    }

    // ------------------------------------------------------------------ storage-backstop-is-internal-error

    @Test
    @DisplayName("REQ: storage-backstop-is-internal-error — a repeated account is never reported as 409 Conflict; its fresh Idempotency-Key stays unused")
    void repeatedAccountIsNot409() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        Response r = post(key, entry(line(a, 300), line(b, -100), line(a, -200)));
        assertThat(r.statusCode()).as("a repeated account must not be a 409 (body %s)", r.asString()).isNotEqualTo(409);
        assertRule(r, DEGENERATE);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: storage-backstop-is-internal-error — precedence: an already-used Idempotency-Key on a repeated-account entry is 422 degenerate-entry (validation precedes the key check), never 409 or idempotency-key-reused; original intact")
    void precedenceUsedKeyOnRepeatedAccount() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String key = newKey();
        postOk(key, entry(line(a, 300), line(b, -300)));

        Response r = post(key, entry(line(c, 10), line(c, -10)));

        String detail = assertRule(r, DEGENERATE);
        assertThat(detail).as("detail must identify the repeated account_code").contains(c);
        assertNotReplayed(r);
        assertBalance(a, 300);
        assertBalance(b, -300);
        assertUntouched(c);
    }

    // ------------------------------------------------------------------ ledger-rule-problem-types

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — +100 / -99 on distinct accounts is 422 with type unbalanced-entry, nothing created")
    void unbalancedType() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        assertRule(post(key, entry(line(a, 100), line(b, -99))), UNBALANCED);
        assertUntouched(a, b);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — each degenerate case (empty lines, zero-amount line, repeated account) is type degenerate-entry with a detail naming that case")
    void degenerateCasesNamed() {
        String empty = assertRule(post(newKey(), "{\"lines\":[]}"), DEGENERATE);
        List<String> z = newAccounts(3);
        String zero = assertRule(post(newKey(), entryOf(z, cents(250, 0, -250))), DEGENERATE);
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String repeat = assertRule(post(newKey(), entry(line(a, 100), line(a, 50), line(b, -150))), DEGENERATE);

        assertStatesTwoLineMinimum(empty);
        assertMentionsZero(zero);
        assertThat(repeat).as("repeated-account detail must name the account").contains(a);
        assertThat(new HashSet<>(List.of(empty, zero, repeat)))
                .as("each degenerate case needs its own detail: [%s] [%s] [%s]", empty, zero, repeat)
                .hasSize(3);
    }

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — a balanced entry naming one unregistered (well-formed) account is 422 with type unknown-account, nothing created")
    void unknownAccountType() {
        String a = newAccount("ASSET");
        String unknown = uniqueCode();
        String key = newKey();
        assertRule(post(key, entry(line(a, 100), line(unknown, -100))), UNKNOWN_ACCOUNT);
        assertUntouched(a);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — [Long.MAX_VALUE, -Long.MAX_VALUE] on distinct accounts is 422 with type amount-limit-exceeded, nothing created")
    void amountLimitType() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        assertRule(post(key, entry(line(a, LONG_MAX), line(b, LONG_MAX.negate()))), AMOUNT_LIMIT);
        assertUntouched(a, b);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — amount at the permitted limit (largest accepted, found by bisection) posts 201 and is echoed exactly")
    void amountAtLimitAccepted() {
        BigInteger limit = permittedLimit();
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        List<BigInteger> amounts = List.of(limit, limit.negate());
        assertLines(postOk(entryOf(List.of(a, b), amounts)), List.of(a, b), amounts);
        assertBalance(a, limit);
        assertBalance(b, limit.negate());
    }

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — one centavo over the limit on the debit side ([limit+1, -limit, -1]) is 422 amount-limit-exceeded, nothing created")
    void onePastLimitDebit() {
        BigInteger limit = permittedLimit();
        List<String> codes = newAccounts(3);
        String key = newKey();
        List<BigInteger> amounts = List.of(limit.add(BigInteger.ONE), limit.negate(), BigInteger.ONE.negate());
        assertRule(post(key, entryOf(codes, amounts)), AMOUNT_LIMIT);
        assertUntouched(codes);
        assertKeyStillFree(key);
    }

    @Test
    @DisplayName("REQ: ledger-rule-problem-types — one centavo over the limit on the credit side ([-(limit+1), limit, 1]) is 422 amount-limit-exceeded, nothing created")
    void onePastLimitCredit() {
        BigInteger limit = permittedLimit();
        List<String> codes = newAccounts(3);
        String key = newKey();
        List<BigInteger> amounts = List.of(limit.add(BigInteger.ONE).negate(), limit, BigInteger.ONE);
        assertRule(post(key, entryOf(codes, amounts)), AMOUNT_LIMIT);
        assertUntouched(codes);
        assertKeyStillFree(key);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"9223372036854775808", "1000000000000000000000000000000"})
    @DisplayName("REQ: ledger-rule-problem-types — an amount_cents beyond 64 bits is 400 (default type) or 422 amount-limit-exceeded (spec ambiguous); never 500, nothing created")
    void beyondSixtyFourBits(String amount) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        BigInteger v = new BigInteger(amount);
        Response r = post(newKey(), entry(line(a, v), line(b, v.negate())));
        observe("amount_cents " + amount + " (beyond 64 bits)", r);
        assertProblemIn(r, 400, 422);
        if (r.statusCode() == 400) assertDefaultType(r);
        else assertThat(typeOf(r)).as("problem type").isEqualTo(AMOUNT_LIMIT);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"400 malformed JSON", "400 missing Idempotency-Key", "400 unknown property",
            "404 unregistered account balance", "405 PUT on the collection", "409 duplicate account code",
            "415 text/plain"})
    @DisplayName("REQ: ledger-rule-problem-types — errors other than 422 keep the default problem type (absent or about:blank), never a ledger-rule URN")
    void nonRuleErrorsKeepDefaultType(String kind) {
        int expected = Integer.parseInt(kind.substring(0, 3));
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String valid = entry(line(a, 5), line(b, -5));
        Response r = switch (kind) {
            case "400 malformed JSON" -> post(newKey(), "{\"lines\":[");
            case "400 missing Idempotency-Key" -> postWithoutKey(valid);
            case "400 unknown property" -> post(newKey(), json("memo", "x", "lines", array(line(a, 5), line(b, -5))));
            case "404 unregistered account balance" -> balance(uniqueCode());
            case "405 PUT on the collection" -> given().contentType(ContentType.JSON).header(IDEMPOTENCY_KEY, newKey())
                    .body(valid).when().put(ENTRIES);
            // A repeated Idempotency-Key is no longer a 409 (idempotent-replay): it replays or is a 422 rule
            // with its own type. The 409 that remains in the API is a duplicate account code.
            case "409 duplicate account code" -> given().contentType(ContentType.JSON)
                    .body(json("code", a, "type", "ASSET")).when().post("/accounts");
            case "415 text/plain" -> given().contentType("text/plain").header(IDEMPOTENCY_KEY, newKey())
                    .body(valid.getBytes(StandardCharsets.UTF_8)).when().post(ENTRIES);
            default -> throw new IllegalArgumentException(kind);
        };
        assertProblem(r, expected);
        assertDefaultType(r);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"zero line in an unbalanced entry", "repeated account in an unbalanced entry",
            "zero line on an unknown account", "repeated unknown account", "single line on an unknown account",
            "zero line beside over-limit lines", "repeated account with over-limit lines",
            "sum overflowing 64 bits (wraps to exactly zero)"})
    @DisplayName("REQ: ledger-rule-problem-types — precedence (spec silent): an entry breaking two ledger rules is 422 with one of their types, nothing created")
    void precedenceUnspecified(String kind) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String c = newAccount("REVENUE");
        String u = uniqueCode();
        BigInteger max = LONG_MAX;
        String body;
        List<String> allowed;
        switch (kind) {
            case "zero line in an unbalanced entry" -> {
                body = entry(line(a, 100), line(b, 0), line(c, -99));
                allowed = List.of(DEGENERATE, UNBALANCED);
            }
            case "repeated account in an unbalanced entry" -> {
                body = entry(line(a, 100), line(a, 50), line(b, -100));
                allowed = List.of(DEGENERATE, UNBALANCED);
            }
            case "zero line on an unknown account" -> {
                body = entry(line(a, 100), line(u, 0), line(b, -100));
                allowed = List.of(DEGENERATE, UNKNOWN_ACCOUNT);
            }
            case "repeated unknown account" -> {
                body = entry(line(u, 100), line(u, -50), line(a, -50));
                allowed = List.of(DEGENERATE, UNKNOWN_ACCOUNT);
            }
            case "single line on an unknown account" -> {
                body = entry(line(u, 100));
                allowed = List.of(DEGENERATE, UNKNOWN_ACCOUNT);
            }
            case "zero line beside over-limit lines" -> {
                body = entry(line(a, max), line(b, max.negate()), line(c, 0));
                allowed = List.of(DEGENERATE, AMOUNT_LIMIT);
            }
            case "repeated account with over-limit lines" -> {
                body = entry(line(a, max), line(a, max.negate()));
                allowed = List.of(DEGENERATE, AMOUNT_LIMIT);
            }
            case "sum overflowing 64 bits (wraps to exactly zero)" -> {
                body = entry(line(a, max), line(b, max), line(c, 2));
                allowed = List.of(UNBALANCED, AMOUNT_LIMIT);
            }
            default -> throw new IllegalArgumentException(kind);
        }
        Response r = post(newKey(), body);
        observe("precedence: " + kind, r);
        assertProblem(r, 422);
        assertThat(typeOf(r)).as("problem type (body %s)", r.asString()).isIn(allowed);
        assertUntouched(a, b, c);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"empty lines", "zero-amount line", "repeated account"})
    @DisplayName("REQ: ledger-rule-problem-types — precedence: an unknown top-level property is 400 (default type) even on a degenerate entry; never ignored in favour of the 422")
    void precedenceUnknownPropertyOverDegenerate(String kind) {
        List<String> codes = newAccounts(3);
        Raw lines = switch (kind) {
            case "empty lines" -> array();
            case "zero-amount line" -> array(line(codes.get(0), 250), line(codes.get(1), 0), line(codes.get(2), -250));
            case "repeated account" -> array(line(codes.get(0), 100), line(codes.get(0), 50), line(codes.get(1), -150));
            default -> throw new IllegalArgumentException(kind);
        };
        Response r = post(newKey(), json("memo", "x", "lines", lines));
        assertProblem(r, 400);
        assertDefaultType(r);
        assertUntouched(codes);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"text/plain", "application/xml"})
    @DisplayName("REQ: ledger-rule-problem-types — precedence: a degenerate entry sent with a non-JSON Content-Type is 415 (default type), not a ledger-rule 422")
    void precedenceMediaTypeOverDegenerate(String contentType) {
        String a = newAccount("ASSET");
        byte[] body = entry(line(a, 700), line(a, -700)).getBytes(StandardCharsets.UTF_8);
        Response r = given().contentType(contentType).header(IDEMPOTENCY_KEY, newKey())
                .body(body).when().post(ENTRIES);
        assertProblem(r, 415);
        assertDefaultType(r);
        assertUntouched(a);
    }

    // ------------------------------------------------------------------ helpers

    /** "Creates nothing": the rejected post's key was never consumed, so a valid post can still use it. */
    void assertKeyStillFree(String key) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response r = post(key, entry(line(a, 1), line(b, -1)));
        assertThat(r.statusCode())
                .as("a rejected post must leave its Idempotency-Key unused, but reusing it gave: %s", r.asString())
                .isEqualTo(201);
    }

    static void assertStatesTwoLineMinimum(String detail) {
        String d = detail.toLowerCase(Locale.ROOT);
        boolean saysTwo = d.contains("two") || d.matches("(?s).*\\b2\\b.*");
        assertThat(d.contains("line") && saysTwo)
                .as("detail must state that an entry needs at least two lines: %s", detail).isTrue();
    }

    static void assertMentionsZero(String detail) {
        String d = detail.toLowerCase(Locale.ROOT);
        assertThat(d.contains("zero") || d.matches("(?s).*\\b0\\b.*"))
                .as("detail must say the amount is zero: %s", detail).isTrue();
    }

    /**
     * The detail identifies a line by its account_code or by its 1-based position, the numbering the
     * response's {@code line_number} uses ("line 2", "line_number 2", "second line").
     */
    static void assertIdentifiesLine(String detail, String accountCode, int lineNumber) {
        String d = detail.toLowerCase(Locale.ROOT);
        String ordinal = switch (lineNumber) {
            case 1 -> "first";
            case 2 -> "second";
            case 3 -> "third";
            default -> throw new IllegalArgumentException("line " + lineNumber);
        };
        boolean byCode = detail.contains(accountCode);
        boolean byNumber = d.matches("(?s).*\\b(line number|line_number|line|position)\\s*[#:=]?\\s*" + lineNumber + "\\b.*");
        boolean byOrdinal = d.contains(ordinal + " line");
        assertThat(byCode || byNumber || byOrdinal)
                .as("detail must identify line %d (account_code %s): %s", lineNumber, accountCode, detail)
                .isTrue();
    }
}
