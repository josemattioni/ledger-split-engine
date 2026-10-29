package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box contract for the ledger-append-only delta of the immutable-accounts-and-as-of-balance
 * slice, written from
 * openspec/changes/archive/2026-10-23-immutable-accounts-and-as-of-balance/specs/ledger-append-only/spec.md and
 * PROJECT-CONVENTIONS.md only.
 *
 * <p>Requirement slugs used in the display names:
 * <ul>
 *   <li>{@code app-identity-read-insert-only}: "The application's database identity can only read
 *       and insert" (modified: accounts included, INSERT only on client-supplied columns)
 *   <li>{@code ledger-rows-immutable-even-for-owner}: "Ledger rows cannot be changed or removed even
 *       by the table owner" (modified: accounts included)
 *   <li>{@code server-assigned-from-defaults}: "Server-assigned columns can only come from their
 *       defaults"
 *   <li>{@code later-tables-read-only-until-granted}: "Tables created by later migrations are
 *       read-only for the application until granted"
 * </ul>
 *
 * <p>Every requirement is stated at the database level (privileges, triggers, column grants). This
 * suite is HTTP and JSON only, so those scenarios are not in it: docs/requirements-below-http.md
 * names the test that covers each. What HTTP can observe is tested: no API method changes or
 * removes an account, with or without lines; a
 * request cannot supply a server-assigned value; and the correction the conventions prescribe for
 * a mistyped account (register the right one, move the balance with an entry) works and leaves
 * idempotent replay of the old entry intact.
 */
class ImmutableAccountsContractTest extends JournalSliceContractSupport {

    static final String CANONICAL_UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    // ------------------------------------------------------------------ app-identity-read-insert-only

    @Test
    @DisplayName("REQ: app-identity-read-insert-only — registering accounts, posting an entry, reversing it and reading current and as-of balances all succeed under the application identity")
    void apiWorksUnderAppIdentity() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Response posted = postOk(entry(line(a, 640), line(b, -640)));
        assertBalance(a, 640);

        Response asOf = given().queryParam("as_of", "2020-01-01T00:00:00Z").when().get(BALANCE, a);
        assertThat(asOf.statusCode()).as("as-of balance (body %s)", asOf.asString()).isEqualTo(200);
        assertThat(integer(asOf.jsonPath().get("balance_cents"), "balance_cents")).isZero();

        Response reversal = given().header(IDEMPOTENCY_KEY, newKey()).when()
                .post(ENTRIES + "/{entry_id}/reversal", posted.jsonPath().getString("entry_id"));
        assertThat(reversal.statusCode()).as("reversal (body %s)", reversal.asString()).isEqualTo(201);
        assertBalance(a, 0);
        assertBalance(b, 0);
    }

    // ------------------------------------------------------------------ ledger-rows-immutable-even-for-owner

    @ParameterizedTest(name = "[{index}] {0}, with lines: {1}")
    @CsvSource({"PUT, false", "PATCH, false", "DELETE, false", "PUT, true", "PATCH, true", "DELETE, true"})
    @DisplayName("REQ: ledger-rows-immutable-even-for-owner — a write to /accounts/{code}, with or without lines, is 404/405 problem+json; the account (every member) and its balance are unchanged and it still accepts entries")
    void accountCannotBeChanged(String method, boolean withLines) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        if (withLines) postOk(entry(line(a, 500), line(b, -500)));
        Map<String, Object> before = lookupBody(a);

        Response r = given().contentType(ContentType.JSON).body(json("code", a, "type", "EXPENSE", "owner_ref", "changed"))
                .when().request(method, "/accounts/{code}", a);

        observe(method + " /accounts/{code} (with lines: " + withLines + ")", r);
        assertProblemIn(r, 404, 405);
        assertThat(lookupBody(a)).as("the account is unchanged").isEqualTo(before);
        assertBalance(a, withLines ? 500 : 0);
        postOk(entry(line(a, 1), line(b, -1)));
        assertBalance(a, withLines ? 501 : 1);
    }

    @ParameterizedTest(name = "[{index}] {0} /accounts")
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    @DisplayName("REQ: ledger-rows-immutable-even-for-owner — a write to the /accounts collection is 404/405 problem+json and removes or changes no account")
    void accountCollectionCannotBeChanged(String method) {
        String a = newAccount("ASSET");
        Map<String, Object> before = lookupBody(a);

        Response r = given().contentType(ContentType.JSON).body("[" + json("code", a, "type", "EXPENSE") + "]")
                .when().request(method, "/accounts");

        observe(method + " /accounts", r);
        assertProblemIn(r, 404, 405);
        assertThat(lookupBody(a)).as("the account is unchanged").isEqualTo(before);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "a different type | LIABILITY | seller-1",
            "a different owner_ref | ASSET | seller-2",
            "owner_ref removed | ASSET | ",
            "the identical request | ASSET | seller-1"})
    @DisplayName("REQ: ledger-rows-immutable-even-for-owner — registering an existing code again is a 4xx problem+json and never overwrites the account")
    void reRegistrationDoesNotOverwrite(String label, String type, String ownerRef) {
        String code = uniqueCode();
        register(json("code", code, "type", "ASSET", "owner_ref", "seller-1"), 201);
        Map<String, Object> before = lookupBody(code);

        Response r = given().contentType(ContentType.JSON).body(json("code", code, "type", type, "owner_ref", ownerRef))
                .when().post("/accounts");

        observe("re-registration with " + label, r);
        assertThat(r.statusCode()).as("status (body %s)", r.asString()).isBetween(400, 499);
        assertProblemShape(r);
        assertThat(lookupBody(code)).as("the account is unchanged").isEqualTo(before);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"X-HTTP-Method-Override header", "_method form field"})
    @DisplayName("REQ: ledger-rows-immutable-even-for-owner — a POST to /accounts/{code} disguised as DELETE (method-override header or _method field) is a 4xx problem+json and the account remains")
    void methodOverrideDoesNotDelete(String how) {
        String a = newAccount("ASSET");
        Map<String, Object> before = lookupBody(a);

        Response r = how.startsWith("X-")
                ? given().header("X-HTTP-Method-Override", "DELETE").when().post("/accounts/{code}", a)
                : given().contentType("application/x-www-form-urlencoded").body("_method=DELETE").when().post("/accounts/{code}", a);

        observe("POST /accounts/{code} with " + how, r);
        assertThat(r.statusCode()).as("status (body %s)", r.asString()).isBetween(400, 499);
        assertProblemShape(r);
        assertThat(lookupBody(a)).as("the account remains, unchanged").isEqualTo(before);
    }

    @Test
    @DisplayName("REQ: ledger-rows-immutable-even-for-owner — a mistyped account is corrected as the conventions prescribe: the right account is registered and the balance moved with an entry; the mistyped account stays registered and unchanged, still accepts entries, and the original entry still replays with the mistyped code")
    void mistypedAccountCorrectedByNewAccount() {
        String mistyped = newAccount("ASSET");
        String counter = newAccount("LIABILITY");
        Map<String, Object> mistypedBefore = lookupBody(mistyped);
        String key = newKey();
        String body = entry(line(mistyped, 900), line(counter, -900));
        Response original = postOk(key, body);

        String right = newAccount("ASSET");
        postOk(entry(line(right, 900), line(mistyped, -900)));

        assertBalance(mistyped, 0);
        assertBalance(right, 900);
        assertBalance(counter, -900);
        assertThat(lookupBody(mistyped)).as("the mistyped account is unchanged").isEqualTo(mistypedBefore);
        Response retry = post(key, body);
        assertReplayOf(retry, original);
        assertThat(retry.jsonPath().getList("lines.account_code")).as("the replay still names the mistyped account")
                .containsExactly(mistyped, counter);
        postOk(entry(line(mistyped, 1), line(counter, -1)));
        assertBalance(mistyped, 1);
    }

    // ------------------------------------------------------------------ server-assigned-from-defaults

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "currency | \"BRL\"",
            "currency | \"USD\"",
            "created_at | \"2020-01-01T00:00:00Z\"",
            "id | 1",
            "id | \"1\""})
    @DisplayName("REQ: server-assigned-from-defaults — a registration that supplies a server-assigned member (currency, created_at, id) is 400 problem+json and registers nothing")
    void registrationCannotSupplyServerValue(String member, String value) {
        String code = uniqueCode();

        Response r = given().contentType(ContentType.JSON).body(json("code", code, "type", "ASSET", member, new Raw(value)))
                .when().post("/accounts");

        observe("registration supplying " + member, r);
        assertProblem(r, 400);
        assertDefaultType(r);
        assertProblem(given().when().get("/accounts/{code}", code), 404);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"entry_id", "created_at", "id", "currency"})
    @DisplayName("REQ: server-assigned-from-defaults — a journal entry that supplies a server-assigned member at the top level (entry_id, created_at, id, currency) is 400 problem+json and posts nothing")
    void entryCannotSupplyServerValue(String member) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");

        Response r = post(newKey(), json("lines", array(line(a, 100), line(b, -100)), member, serverValue(member)));

        observe("entry supplying " + member, r);
        assertProblem(r, 400);
        assertDefaultType(r);
        assertUntouched(a, b);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"id", "journal_entry_id", "created_at", "currency"})
    @DisplayName("REQ: server-assigned-from-defaults — an entry line that supplies a server-assigned member (id, journal_entry_id, created_at, currency) is 400 problem+json and posts nothing")
    void lineCannotSupplyServerValue(String member) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String first = json("account_code", a, "amount_cents", 100, member, serverValue(member));

        Response r = post(newKey(), entry(first, line(b, -100)));

        observe("entry line supplying " + member, r);
        assertProblem(r, 400);
        assertDefaultType(r);
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: server-assigned-from-defaults — values the server assigns are present without being sent: the account's and the balance's currency is BRL, entry_id is a canonical UUID, and the entry's created_at is no earlier than its account's")
    void serverAssignedValuesArePresent() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        Map<String, Object> account = lookupBody(a);

        Response posted = postOk(entry(line(a, 300), line(b, -300)));

        assertThat(account.get("currency")).as("account currency").isEqualTo("BRL");
        assertThat(balance(a).jsonPath().getString("currency")).as("balance currency").isEqualTo("BRL");
        assertThat(posted.jsonPath().getString("entry_id")).as("entry_id").matches(CANONICAL_UUID);
        Instant registered = OffsetDateTime.parse((String) account.get("created_at")).toInstant();
        Instant recorded = OffsetDateTime.parse(posted.jsonPath().getString("created_at")).toInstant();
        assertThat(recorded).as("entry created_at vs its account's created_at").isAfterOrEqualTo(registered);
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> lookupBody(String code) {
        Response r = given().when().get("/accounts/{code}", code);
        assertThat(r.statusCode()).as("lookup of %s should be 200: %s", code, r.asString()).isEqualTo(200);
        return r.jsonPath().getMap("");
    }

    void register(String body, int expectedStatus) {
        Response r = given().contentType(ContentType.JSON).body(body).when().post("/accounts");
        assertThat(r.statusCode()).as("precondition: registration should be %d: %s", expectedStatus, r.asString())
                .isEqualTo(expectedStatus);
    }

    /** A plausible value of the right JSON type for each server-assigned member. */
    static Raw serverValue(String member) {
        return switch (member) {
            case "entry_id" -> new Raw(quote(UUID.randomUUID().toString()));
            case "created_at" -> new Raw(quote("2020-01-01T00:00:00Z"));
            case "currency" -> new Raw(quote("BRL"));
            default -> new Raw("1");
        };
    }
}
