package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box contract for the ledger-append-only capability of the
 * append-only-ledger-and-entry-guards slice, written from
 * openspec/changes/append-only-ledger-and-entry-guards/specs/ledger-append-only/spec.md and
 * PROJECT-CONVENTIONS.md only.
 *
 * <p>Every requirement of this capability is stated at the database level (privileges, triggers,
 * migration history). This suite is HTTP and JSON only, so those scenarios are not in it:
 * docs/requirements-below-http.md names the test that covers each. What HTTP can observe is tested:
 * the API works end to end while the app connects as the application identity (ContractTestBase wires it as
 * {@code ledger_app}), no API method edits or removes a posted entry or an account with lines, and
 * a correction is a new entry. Tests derived from the conventions rather than from a spec scenario
 * name the capability, {@code ledger-append-only}.
 *
 * <p>That a posted entry is intact is observed by re-sending its original post: under the
 * idempotent-replay spec that replays the entry exactly as first returned and writes nothing.
 */
class LedgerAppendOnlyContractTest extends JournalSliceContractSupport {

    // ------------------------------------------------------------------ app-identity-read-insert-only

    @Test
    @DisplayName("REQ: app-identity-read-insert-only — registering accounts, posting entries and reading accounts and balances all succeed under the application identity")
    void apiWorksUnderAppIdentity() {
        List<String> codes = newAccounts(4);
        for (String code : codes) {
            Response lookup = given().when().get("/accounts/{code}", code);
            assertThat(lookup.statusCode()).as("lookup of %s: %s", code, lookup.asString()).isEqualTo(200);
        }
        Response first = postOk(entryOf(codes, cents(1000, -600, -300, -100)));
        Response second = postOk(entry(line(codes.get(0), -250), line(codes.get(1), 250)));

        assertThat(first.jsonPath().getString("entry_id"))
                .as("each post gets its own server-generated entry_id")
                .isNotBlank()
                .isNotEqualTo(second.jsonPath().getString("entry_id"));
        assertBalance(codes.get(0), 750);
        assertBalance(codes.get(1), -350);
        assertBalance(codes.get(2), -300);
        assertBalance(codes.get(3), -100);
    }

    // later-tables-same-restricted-access was removed by immutable-accounts-and-as-of-balance; its
    // replacement, later-tables-read-only-until-granted, is a database-level scenario (see docs/requirements-below-http.md).

    // ------------------------------------------------------------------ ledger-append-only (API surface)

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"PUT", "PATCH", "DELETE", "POST"})
    @DisplayName("REQ: ledger-append-only — a write to /journal-entries/{entry_id} is 404/405 problem+json and the posted entry stays intact: re-sending its post replays it exactly as first returned")
    void postedEntryCannotBeChanged(String method) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 500), line(b, -500));
        Response original = postOk(key, body);
        String entryId = original.jsonPath().getString("entry_id");

        Response r = given().contentType(ContentType.JSON).header(IDEMPOTENCY_KEY, newKey())
                .body(entry(line(a, 1), line(b, -1)))
                .when().request(method, ENTRIES + "/{id}", entryId);
        observe(method + " /journal-entries/{entry_id}", r);

        assertProblemIn(r, 404, 405);
        assertBalance(a, 500);
        assertBalance(b, -500);
        assertReplayOf(post(key, body), original);
        assertBalance(a, 500);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    @DisplayName("REQ: ledger-append-only — a write to an account that has lines is 404/405 problem+json; the account and its balance are unchanged")
    void accountWithLinesCannotBeChanged(String method) {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        postOk(entry(line(a, 500), line(b, -500)));

        Response r = given().contentType(ContentType.JSON).body(json("code", a, "type", "EXPENSE"))
                .when().request(method, "/accounts/{code}", a);
        observe(method + " /accounts/{code} with lines", r);

        assertProblemIn(r, 404, 405);
        Response lookup = given().when().get("/accounts/{code}", a);
        assertThat(lookup.statusCode()).as("account must still exist: %s", lookup.asString()).isEqualTo(200);
        assertThat(lookup.jsonPath().getString("type")).as("account type").isEqualTo("ASSET");
        assertBalance(a, 500);
    }

    @Test
    @DisplayName("REQ: ledger-append-only — a correction is a new entry: the reverse posts 201 with its own entry_id, the original keeps its key (re-sending it replays the original, posting nothing), and balances net to zero")
    void correctionIsANewEntry() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String key = newKey();
        String body = entry(line(a, 500), line(b, -500));
        Response original = postOk(key, body);

        Response reversal = postOk(entry(line(a, -500), line(b, 500)));

        assertThat(reversal.jsonPath().getString("entry_id"))
                .as("the correction is a separate entry")
                .isNotEqualTo(original.jsonPath().getString("entry_id"));
        assertBalance(a, 0);
        assertBalance(b, 0);
        assertReplayOf(post(key, body), original);
        assertBalance(a, 0);
        assertBalance(b, 0);

        postOk(entry(line(a, 400), line(b, -400)));
        assertBalance(a, 400);
        assertBalance(b, -400);
    }
}
