package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box contract for the account-balances capability of the add-journal-entries slice,
 * written from openspec/changes/add-journal-entries/specs/account-balances/spec.md and
 * PROJECT-CONVENTIONS.md only. Endpoint: {@code GET /api/v1/accounts/{code}/balance}.
 *
 * <p>The large-balance tests follow append-only-ledger-and-entry-guards, which caps each line at
 * a permitted amount range: balances are built from lines at that limit, not at 64 bits.
 */
class AccountBalanceContractTest extends JournalSliceContractSupport {

    // ------------------------------------------------------------------ read-derived-balance

    @Test
    @DisplayName("REQ: read-derived-balance — balance response is 200 JSON with exactly account_code (the account's code), currency, balance_cents and as_of")
    void balanceShape() {
        // The exact member set is from openspec/changes/archive/2026-10-23-immutable-accounts-and-as-of-balance/specs/account-balances/spec.md.
        String code = newAccount("ASSET");
        Response r = balance(code);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isEqualTo(200);
        assertThat(r.contentType()).as("Content-Type").startsWith("application/json");
        Map<String, Object> body = r.jsonPath().getMap("");
        assertThat(body).as("balance body").containsOnlyKeys("account_code", "currency", "balance_cents", "as_of");
        assertThat(body.get("account_code")).as("account_code").isEqualTo(code);
    }

    @Test
    @DisplayName("REQ: read-derived-balance — balance currency is the account's own currency")
    void balanceCurrencyMatchesAccount() {
        String code = newAccount("LIABILITY");
        String accountCurrency = given().when().get("/accounts/{code}", code).jsonPath().getString("currency");
        Response r = balance(code);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getString("currency")).as("currency").isNotBlank().isEqualTo(accountCurrency);
    }

    @Test
    @DisplayName("REQ: read-derived-balance — account with no lines has balance_cents equal to integer 0")
    void zeroWithNoLines() {
        String code = newAccount("REVENUE");
        Response r = balance(code);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isEqualTo(200);
        assertThat(integer(r.jsonPath().get("balance_cents"), "balance_cents")).isZero();
    }

    @Test
    @DisplayName("REQ: read-derived-balance — balance is the signed sum of the account's lines across several entries")
    void sumAcrossEntries() {
        String asset = newAccount("ASSET");
        String liability = newAccount("LIABILITY");
        String revenue = newAccount("REVENUE");
        String expense = newAccount("EXPENSE");
        postOk(entry(line(asset, 1000), line(liability, -1000)));
        postOk(entry(line(asset, -250), line(revenue, 250)));
        postOk(entry(line(expense, 5), line(asset, -5)));

        assertBalance(asset, 745);
        assertBalance(liability, -1000);
        assertBalance(revenue, 250);
        assertBalance(expense, 5);
    }

    @Test
    @DisplayName("REQ: read-derived-balance — credit-normal liability with a negative signed sum is reported negative, not flipped")
    void liabilityNegativeAsIs() {
        String liability = newAccount("LIABILITY");
        String asset = newAccount("ASSET");
        postOk(entry(line(liability, 300), line(asset, -300)));
        postOk(entry(line(asset, 1000), line(liability, -1000)));
        assertBalance(liability, -700);
        assertBalance(asset, 700);
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(strings = {"ASSET", "LIABILITY", "REVENUE", "EXPENSE"})
    @DisplayName("REQ: read-derived-balance — no normalization by type: debit-net reads positive and credit-net reads negative for every type")
    void noNormalizationByType(String type) {
        String credited = newAccount(type);
        String debited = newAccount(type);
        postOk(entry(line(credited, -700), line(debited, 700)));
        assertBalance(credited, -700);
        assertBalance(debited, 700);
    }

    @Test
    @DisplayName("REQ: read-derived-balance — balance_cents is a JSON integer, never a decimal or string")
    void balanceIsInteger() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        postOk(entry(line(a, 987654321L), line(b, -987654321L)));
        Response r = balance(b);
        assertThat(integer(r.jsonPath().get("balance_cents"), "balance_cents")).isEqualTo(BigInteger.valueOf(-987654321L));
        assertThat(r.asString()).as("raw body")
                .doesNotContainPattern("\"balance_cents\"\\s*:\\s*-?\\d+\\.")
                .doesNotContainPattern("\"balance_cents\"\\s*:\\s*\"");
    }

    @Test
    @DisplayName("REQ: read-derived-balance — balances built from lines at the permitted limit are reported exactly, including a sum beyond one line's limit")
    void balanceAtLineLimitExact() {
        BigInteger limit = permittedLimit();
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        postOk(entry(line(a, limit), line(b, limit.negate())));
        assertBalance(a, limit);
        assertBalance(b, limit.negate());

        postOk(entry(line(a, limit), line(b, limit.negate())));
        assertBalance(a, limit.multiply(BigInteger.TWO));
        assertBalance(b, limit.multiply(BigInteger.TWO).negate());
    }

    @Test
    @DisplayName("REQ: read-derived-balance — balance response keys are snake_case and expose no internal id")
    void balanceKeys() {
        String code = newAccount("EXPENSE");
        Map<String, Object> body = balance(code).jsonPath().getMap("");
        assertSnakeCaseKeys(body, "balance response");
        assertNoInternalId(body, Set.of(), "balance response");
    }

    // ------------------------------------------------------------------ balance-requires-registered-account

    @Test
    @DisplayName("REQ: balance-requires-registered-account — unregistered code returns 404 problem+json whose detail names the code")
    void unknownAccount404() {
        String code = uniqueCode();
        String detail = assertProblem(balance(code), 404);
        assertThat(detail).as("404 detail should say no account exists for that code").contains(code);
    }

    @Test
    @DisplayName("REQ: balance-requires-registered-account — upper-cased variant of a registered code is not a match (4xx problem+json)")
    void caseVariantNotMatched() {
        String code = newAccount("ASSET");
        Response r = balance(code.toUpperCase(Locale.ROOT));
        assertThat(r.statusCode()).as("body: %s", r.asString()).isBetween(400, 499);
        assertProblemShape(r);
    }

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @ValueSource(strings = {"Not A Valid Code", "UPPER", "9digit", "a:b:c", " "})
    @DisplayName("REQ: balance-requires-registered-account — malformed code is a 4xx problem+json, never 200 or 500")
    void malformedCode(String code) {
        Response r = balance(code);
        observe("balance of malformed code '" + code + "'", r);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isBetween(400, 499);
        assertProblemShape(r);
    }

    // ------------------------------------------------------------------ balance-errors-problem-json

    @Test
    @DisplayName("REQ: balance-errors-problem-json — not-found balance is application/problem+json with a body, not a bare status")
    void notFoundIsProblemJson() {
        Response r = balance(uniqueCode());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.contentType()).as("Content-Type").startsWith(PROBLEM_JSON);
        assertThat(r.asString()).as("body").isNotBlank();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("REQ: balance-errors-problem-json — unsupported method on a balance returns 405 problem+json")
    void methodNotAllowed(String method) {
        String code = newAccount("ASSET");
        Response r = given().contentType(ContentType.JSON).body("{\"balance_cents\":1}")
                .when().request(method, BALANCE, code);
        assertProblem(r, 405);
        assertBalance(code, 0);
    }

    @Test
    @DisplayName("REQ: balance-errors-problem-json — Accept: application/xml on an unknown balance yields 404 or 406, never a bare body")
    void errorWithXmlAccept() {
        Response r = given().accept("application/xml").when().get(BALANCE, uniqueCode());
        observe("Accept xml on unknown balance", r);
        assertThat(r.statusCode()).as("body: %s", r.asString()).isIn(404, 406);
        assertThat(r.asString()).as("error must carry a body").isNotBlank();
    }
}
