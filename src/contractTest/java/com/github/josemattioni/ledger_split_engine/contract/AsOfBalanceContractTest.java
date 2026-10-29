package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Black-box contract for the account-balances delta of the immutable-accounts-and-as-of-balance
 * slice, written from
 * openspec/changes/archive/2026-10-23-immutable-accounts-and-as-of-balance/specs/account-balances/spec.md and
 * PROJECT-CONVENTIONS.md only. Endpoint: {@code GET /api/v1/accounts/{code}/balance[?as_of=...]}.
 *
 * <p>Requirement slugs used in the display names:
 * <ul>
 *   <li>{@code read-derived-balance}: "Read an account's derived balance" (modified)
 *   <li>{@code as-of-balance}: "A balance can be read as of a past instant"
 *   <li>{@code as-of-rfc3339}: "The as_of parameter is a strict RFC 3339 instant"
 *   <li>{@code as-of-only-parameter}: "Query parameters other than as_of are rejected"
 *   <li>{@code settled-instants-only}: "A balance is answered only for settled instants"
 *   <li>{@code balance-error-order}: "Balance errors are chosen in a fixed order"
 *   <li>{@code settled-answer-final}: "A settled answer cannot change after the fact"
 * </ul>
 * Tests named {@code unknown-query-parameters} come from PROJECT-CONVENTIONS.md, which states that
 * rule for every endpoint, while the spec states it for balance requests only.
 *
 * <p>Every balance request here goes over a raw socket, so the query string reaches the server
 * byte for byte: no client re-encodes a {@code +}, refuses a {@code %zz} or merges a repeated name.
 *
 * <p>The database's clock is read from the {@code created_at} of an entry posted just before
 * ({@link #dbNow()}): the conventions make it server-assigned from the database's default, at the
 * start of the entry's transaction, so it is never later than the clock the next request reads.
 * Scenarios that need settled history share one {@link Timeline}, posted once per JVM and then
 * waited out past the 60-second window (about a minute, paid by the first test that uses it).
 * Expected as-of balances come from {@link Timeline#expected}: the sum of the fixture's lines whose
 * {@code created_at} is at or before the instant.
 *
 * <p>Tests whose scenario the spec leaves open accept every defensible outcome, still forbid 500s,
 * and print an {@code OBSERVED} line with what actually happened.
 */
class AsOfBalanceContractTest extends JournalSliceContractSupport {

    static final String NOT_SETTLED = "urn:ledger-split-engine:problem:as-of-not-settled";
    static final Duration WINDOW = Duration.ofSeconds(60);
    /** The one fixed detail for a malformed percent-encoding, per PROJECT-CONVENTIONS.md. */
    static final String BAD_ENCODING = "the query string is not valid percent-encoded UTF-8";
    static final Set<String> BALANCE_KEYS = Set.of("account_code", "currency", "balance_cents", "as_of");
    /** The instant of the spec's own spelling examples. */
    static final Instant SPEC_INSTANT = Instant.parse("2026-10-21T15:00:00Z");
    static final String SPEC_AS_OF = "2026-10-21T15:00:00Z";

    private static Timeline timeline;
    private static RuntimeException timelineFailure;
    private static String clockDebit;
    private static String clockCredit;
    private static boolean clockObserved;

    // ------------------------------------------------------------------ read-derived-balance

    @Test
    @DisplayName("REQ: read-derived-balance — a current balance is 200 application/json with exactly account_code (the requested code), currency, balance_cents (the signed sum) and as_of, present and null")
    void currentBalanceExactBody() {
        String asset = newAccount("ASSET");
        String liability = newAccount("LIABILITY");
        postOk(entry(line(asset, 1234), line(liability, -1234)));
        postOk(entry(line(asset, -34), line(liability, 34)));

        RawResponse r = balanceQuery(asset, null);

        Map<String, Object> body = assertBalanceBody(r, asset);
        assertThat(body.get("as_of")).as("as_of of a current balance").isNull();
        assertThat(r.body()).as("as_of must be present as JSON null, not absent")
                .containsPattern("\"as_of\"\\s*:\\s*null");
        assertThat(integer(body.get("balance_cents"), "balance_cents")).isEqualTo(BigInteger.valueOf(1200));
    }

    @Test
    @DisplayName("REQ: read-derived-balance — a registered account with no lines reads balance_cents 0, with the same exact body and as_of null")
    void currentZeroExactBody() {
        String code = newAccount("REVENUE");

        Map<String, Object> body = assertBalanceBody(balanceQuery(code, null), code);

        assertThat(integer(body.get("balance_cents"), "balance_cents")).isZero();
        assertThat(body.get("as_of")).as("as_of").isNull();
    }

    @Test
    @DisplayName("REQ: read-derived-balance — a liability whose lines sum negative reads that negative value as-is, with the same exact body and as_of null")
    void currentNegativeLiability() {
        String liability = newAccount("LIABILITY");
        String asset = newAccount("ASSET");
        postOk(entry(line(asset, 900), line(liability, -900)));

        Map<String, Object> body = assertBalanceBody(balanceQuery(liability, null), liability);

        assertThat(integer(body.get("balance_cents"), "balance_cents")).isEqualTo(BigInteger.valueOf(-900));
        assertThat(body.get("as_of")).as("as_of").isNull();
    }

    // ------------------------------------------------------------------ as-of-balance

    @Test
    @DisplayName("REQ: as-of-balance — an instant between two entries sees only the earlier ones: between t1 and t2 reads t1's line only, between t2 and t3 reads t1 + t2, after t3 all three")
    void betweenEntriesSeesOnlyEarlier() {
        Timeline t = timeline();
        Instant t1 = t.histAt.get(0);
        Instant t2 = t.histAt.get(1);
        Instant t3 = t.histAt.get(2);

        assertThat(balanceAt(t.hist, midpoint(t1, t2))).as("debit account between t1 and t2").isEqualTo(BigInteger.valueOf(1000));
        assertThat(balanceAt(t.histCounter, midpoint(t1, t2))).as("credit account between t1 and t2").isEqualTo(BigInteger.valueOf(-1000));
        assertThat(balanceAt(t.hist, midpoint(t2, t3))).as("debit account between t2 and t3").isEqualTo(BigInteger.valueOf(1200));
        assertThat(balanceAt(t.hist, t3.plusMillis(500))).as("debit account after t3").isEqualTo(BigInteger.valueOf(1150));
    }

    @Test
    @DisplayName("REQ: as-of-balance — the instant itself is included, to the microsecond: as of exactly an entry's created_at includes its line, one microsecond earlier does not")
    void instantIncludedToTheMicrosecond() {
        Timeline t = timeline();
        for (int i = 0; i < t.histAt.size(); i++) {
            Instant at = t.histAt.get(i);
            Instant before = at.minus(1, ChronoUnit.MICROS);
            assertThat(balanceAt(t.hist, at)).as("as of entry %d's created_at %s", i + 1, at)
                    .isEqualTo(t.expected(t.hist, at));
            assertThat(balanceAt(t.hist, before)).as("one microsecond before entry %d's created_at", i + 1)
                    .isEqualTo(t.expected(t.hist, before));
        }
    }

    @Test
    @DisplayName("REQ: as-of-balance — an entry's created_at sent back verbatim as as_of (only a '+' percent-encoded) is accepted and includes that entry")
    void createdAtVerbatimAccepted() {
        Timeline t = timeline();
        String verbatim = t.histRaw.get(1);

        RawResponse r = asOf(t.hist, verbatim.replace("+", "%2B"));

        observe("as_of = entry 2's created_at verbatim '" + verbatim + "'", r.status(), r.contentType(), r.body());
        assertThat(assertAsOfBody(r, t.hist, t.histAt.get(1))).isEqualTo(BigInteger.valueOf(1200));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"one microsecond before registration", "2000-01-01T00:00:00Z", "0001-01-01T00:00:00Z"})
    @DisplayName("REQ: as-of-balance — a registered account read as of a settled instant before it was registered is 200 with balance_cents 0, its own account_code and the account's currency, not 404")
    void beforeRegistrationIsZero(String when) {
        Timeline t = timeline();
        boolean relative = when.startsWith("one");
        Instant instant = relative ? t.histRegistered.minus(1, ChronoUnit.MICROS) : Instant.parse(when);

        RawResponse r = asOf(t.hist, relative ? utc(instant, 6) : when);

        assertThat(assertAsOfBody(r, t.hist, instant)).as("balance before registration").isZero();
        assertThat(JsonPath.from(r.body()).getString("currency")).as("currency").isEqualTo(accountCurrency(t.hist));
    }

    @Test
    @DisplayName("REQ: as-of-balance — a reversal appears only from when it was recorded: between the original and its reversal the original's effect shows, from the reversal's created_at on it is cancelled")
    void reversalAppearsFromItsRecordedTime() {
        Timeline t = timeline();
        Instant r1 = t.revOriginalAt;
        Instant r2 = t.revReversalAt;

        assertThat(balanceAt(t.rev, midpoint(r1, r2))).as("debit account between original and reversal").isEqualTo(BigInteger.valueOf(700));
        assertThat(balanceAt(t.revCounter, midpoint(r1, r2))).as("credit account between original and reversal").isEqualTo(BigInteger.valueOf(-700));
        assertThat(balanceAt(t.rev, r2.minus(1, ChronoUnit.MICROS))).as("one microsecond before the reversal").isEqualTo(BigInteger.valueOf(700));
        assertThat(balanceAt(t.rev, r2)).as("as of the reversal's created_at").isZero();
        assertThat(balanceAt(t.revCounter, r2)).as("credit account as of the reversal's created_at").isZero();
        assertThat(balanceAt(t.rev, r2.plusMillis(500))).as("after the reversal").isZero();
    }

    @ParameterizedTest(name = "[{index}] offset {0}")
    @ValueSource(strings = {"-03:00", "+05:30", "+18:00", "-18:00", "+00:00", "-00:30", "+14:00"})
    @DisplayName("REQ: as-of-balance — an as_of written with an offset is echoed in the body as the same instant expressed in UTC")
    void echoedInUtc(String offset) {
        assertSettled(SPEC_INSTANT);
        String code = newAccount("ASSET");
        Instant instant = SPEC_INSTANT.plus(123_456, ChronoUnit.MICROS);

        RawResponse r = asOf(code, at(instant, offset, 6));

        observe("as_of echo for offset " + offset, r.status(), r.contentType(), r.body());
        assertAsOfBody(r, code, instant);
    }

    @Test
    @DisplayName("REQ: as-of-balance — the echoed as_of, sent back as as_of, is accepted and gives the identical answer")
    void echoRoundTrips() {
        Timeline t = timeline();
        RawResponse first = asOf(t.hist, at(t.histAt.get(1), "-03:00", 6));
        assertAsOfBody(first, t.hist, t.histAt.get(1));
        String echoed = JsonPath.from(first.body()).getString("as_of");

        RawResponse again = asOf(t.hist, echoed.replace("+", "%2B"));

        assertAsOfBody(again, t.hist, t.histAt.get(1));
        assertThat(JsonPath.from(again.body()).getMap("")).as("answer to the echoed as_of '%s'", echoed)
                .isEqualTo(JsonPath.from(first.body()).getMap(""));
    }

    @Test
    @DisplayName("REQ: as-of-balance — a later posting does not change an earlier answer: the same settled as_of reads the identical body before and after a new entry and a reversal on the account")
    void laterPostingDoesNotChangeAnswer() {
        Timeline t = timeline();
        String asOf = utc(t.lateAt, 6);
        RawResponse first = asOf(t.late, asOf);
        assertThat(assertAsOfBody(first, t.late, t.lateAt)).as("first answer").isEqualTo(BigInteger.valueOf(300));
        BigInteger current = currentOf(t.late);

        postOk(entry(line(t.late, 77), line(t.lateCounter, -77)));
        Response reversal = given().header(IDEMPOTENCY_KEY, newKey()).when()
                .post(ENTRIES + "/{entry_id}/reversal", t.lateEntryId);
        assertThat(reversal.statusCode()).as("precondition: reversal should be 201: %s", reversal.asString()).isEqualTo(201);
        assertThat(currentOf(t.late)).as("precondition: both postings are in the current balance")
                .isEqualTo(current.add(BigInteger.valueOf(77 - 300)));

        RawResponse second = asOf(t.late, asOf);

        assertAsOfBody(second, t.late, t.lateAt);
        assertThat(JsonPath.from(second.body()).getMap("")).as("second answer")
                .isEqualTo(JsonPath.from(first.body()).getMap(""));
    }

    @Test
    @DisplayName("REQ: as-of-balance — a balance as of a settled instant gives the same answer every time it is asked (five reads, identical bodies)")
    void sameAnswerEveryTime() {
        Timeline t = timeline();
        Instant instant = midpoint(t.histAt.get(1), t.histAt.get(2));
        RawResponse first = asOf(t.hist, utc(instant, 6));
        assertThat(assertAsOfBody(first, t.hist, instant)).isEqualTo(BigInteger.valueOf(1200));

        for (int i = 2; i <= 5; i++) {
            RawResponse again = asOf(t.hist, utc(instant, 6));
            assertThat(again.status()).as("read %d status (body %s)", i, again.body()).isEqualTo(200);
            assertThat(again.body()).as("read %d", i).isEqualTo(first.body());
        }
    }

    @Test
    @DisplayName("REQ: as-of-balance — entries are ordered by when they were recorded: after 16 simultaneous posts, as of each created_at (and one microsecond before it) reads exactly the lines recorded at or before that instant")
    void orderedByRecordedTime() {
        Timeline t = timeline();
        TreeSet<Instant> instants = new TreeSet<>(t.concAt);
        observe("distinct created_at among 16 simultaneous posts", 200, "", String.valueOf(instants.size()));

        for (Instant at : instants) {
            Instant before = at.minus(1, ChronoUnit.MICROS);
            assertThat(balanceAt(t.conc, at)).as("as of %s", at).isEqualTo(t.expected(t.conc, at));
            assertThat(balanceAt(t.conc, before)).as("one microsecond before %s", at).isEqualTo(t.expected(t.conc, before));
        }
    }

    @Test
    @DisplayName("REQ: as-of-balance — an as-of balance has the same body as a current one: exactly account_code, currency (the account's), balance_cents, and as_of as a UTC string; snake_case, no internal id")
    void asOfBodyShape() {
        Timeline t = timeline();
        Instant instant = t.histAt.get(2);

        RawResponse r = asOf(t.hist, utc(instant, 6));

        observe("as-of balance body", r.status(), r.contentType(), r.body());
        assertAsOfBody(r, t.hist, instant);
        Map<String, Object> body = JsonPath.from(r.body()).getMap("");
        assertThat(body.get("currency")).as("currency").isEqualTo(accountCurrency(t.hist));
        assertSnakeCaseKeys(body, "as-of balance");
        assertNoInternalId(body, Set.of(), "as-of balance");
    }

    @Test
    @DisplayName("REQ: as-of-balance — a registered account with no lines reads balance_cents 0 as of a settled instant after its registration")
    void settledEmptyAccountIsZero() {
        Timeline t = timeline();
        assertThat(balanceAt(t.empty, t.last())).isZero();
    }

    // ------------------------------------------------------------------ as-of-rfc3339

    @Test
    @DisplayName("REQ: as-of-rfc3339 — the spec's spellings of 2026-10-21T15:00:00Z (Z, +00:00, -03:00, %2D03:00, .000000Z) are answered identically: same balance_cents and same as_of")
    void specSpellingsAnsweredIdentically() {
        assertSettled(SPEC_INSTANT);
        String code = newAccount("ASSET");
        assertIdenticalAnswers(code, SPEC_INSTANT, List.of(
                "2026-10-21T15:00:00Z",
                "2026-10-21T15:00:00%2B00:00",
                "2026-10-21T12:00:00-03:00",
                "2026-10-21T12:00:00%2D03:00",
                "2026-10-21T15:00:00.000000Z"));
    }

    @Test
    @DisplayName("REQ: as-of-rfc3339 — more spellings of one settled instant (+05:30, -18:00, +18:00, .0Z, .000Z, %3A for ':', every character percent-encoded) give the same non-zero balance and the same as_of")
    void moreSpellingsAnsweredIdentically() {
        Timeline t = timeline();
        Instant s = ceilSecond(t.histAt.get(2));
        String z = utc(s, 0);

        assertIdenticalAnswers(t.hist, s, List.of(
                z,
                at(s, "+00:00", 0),
                at(s, "-03:00", 0),
                at(s, "-03:00", 0).replace("-03:00", "%2D03:00"),
                at(s, "+05:30", 0),
                at(s, "-18:00", 0),
                at(s, "+18:00", 0),
                utc(s, 1),
                utc(s, 3),
                utc(s, 6),
                z.replace(":", "%3A"),
                percentEncodeAll(z)));
        assertThat(balanceAt(t.hist, s)).isEqualTo(t.expected(t.hist, s)).isEqualTo(BigInteger.valueOf(1150));
    }

    @ParameterizedTest(name = "{0} fractional digits")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    @DisplayName("REQ: as-of-rfc3339 — an as_of with 0 to 6 fractional digits is accepted and read to the microsecond: the instants just below and just above t2 at that precision read the lines recorded at or before each")
    void fractionDigitsAccepted(int digits) {
        Timeline t = timeline();
        Instant t2 = t.histAt.get(1);
        Instant low = floor(t2, digits);
        Instant high = low.equals(t2) ? low : low.plusNanos(BigInteger.TEN.pow(9 - digits).longValueExact());

        for (Instant instant : List.of(low, high)) {
            RawResponse r = asOf(t.hist, utc(instant, digits));
            assertThat(assertAsOfBody(r, t.hist, instant)).as("as of %s", utc(instant, digits))
                    .isEqualTo(t.expected(t.hist, instant));
        }
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "no offset | 2026-10-21T15:00:00",
            "date only | 2026-10-21",
            "no seconds | 2026-10-21T15:00Z",
            "seven fractional digits | 2026-10-21T15:00:00.1234567Z",
            "nine fractional digits | 2026-10-21T15:00:00.123456789Z",
            "a dot with no digits | 2026-10-21T15:00:00.Z",
            "a comma as decimal separator | 2026-10-21T15:00:00,5Z",
            "epoch seconds | 1761058800",
            "epoch milliseconds | 1761058800000",
            "lowercase t | 2026-10-21t15:00:00Z",
            "lowercase z | 2026-10-21T15:00:00z",
            "a space instead of T | 2026-10-21%2015:00:00Z",
            "month 13 | 2026-13-21T15:00:00Z",
            "month 00 | 2026-00-21T15:00:00Z",
            "day 00 | 2026-10-00T15:00:00Z",
            "day 32 | 2026-10-32T15:00:00Z",
            "April 31 | 2026-04-31T15:00:00Z",
            "February 30 | 2026-02-30T15:00:00Z",
            "February 29 of a common year | 2026-02-29T15:00:00Z",
            "hour 24 | 2026-10-21T24:00:00Z",
            "minute 60 | 2026-10-21T15:60:00Z",
            "second 60 outside a leap second | 2026-10-21T15:00:60Z",
            "single-digit month | 2026-1-21T15:00:00Z",
            "single-digit hour | 2026-10-21T5:00:00Z",
            "two-digit year | 26-10-21T15:00:00Z",
            "five-digit year | 02026-10-21T15:00:00Z",
            "signed year | %2B2026-10-21T15:00:00Z",
            "negative year | -2026-10-21T15:00:00Z",
            "basic format | 20261021T150000Z",
            "week date | 2026-W43-3T15:00:00Z",
            "ordinal date | 2026-294T15:00:00Z",
            "offset without colon | 2026-10-21T15:00:00%2B0300",
            "offset hours only | 2026-10-21T15:00:00%2B03",
            "offset with seconds | 2026-10-21T15:00:00%2B03:00:00",
            "offset minute 60 | 2026-10-21T15:00:00%2B03:60",
            "offset +18:01, one over | 2026-10-21T15:00:00%2B18:01",
            "offset -18:01, one over | 2026-10-21T15:00:00-18:01",
            "offset +19:00 | 2026-10-21T15:00:00%2B19:00",
            "zone name UTC | 2026-10-21T15:00:00UTC",
            "zone name GMT | 2026-10-21T15:00:00GMT",
            "bracketed zone id | 2026-10-21T15:00:00Z%5BUTC%5D",
            "Z followed by an offset | 2026-10-21T15:00:00Z%2B00:00",
            "leading space | %202026-10-21T15:00:00Z",
            "trailing space | 2026-10-21T15:00:00Z%20",
            "trailing newline | 2026-10-21T15:00:00Z%0A",
            "trailing NUL | 2026-10-21T15:00:00Z%00",
            "whitespace only | %20",
            "tab only | %09",
            "a quoted JSON string | %222026-10-21T15:00:00Z%22",
            "the word null | null",
            "the word now | now",
            "Arabic-Indic digits in the year | %D9%A2%D9%A0%D9%A2%D9%A6-10-21T15:00:00Z",
            "fullwidth Z | 2026-10-21T15:00:00%EF%BC%BA",
            "an encoded ampersand inside the value | 2026-10-21T15:00:00Z%26x=1"})
    @DisplayName("REQ: as-of-rfc3339 — an as_of that is not an RFC 3339 instant with an offset (one defect each) is 400 application/problem+json with the default type and a detail naming as_of")
    void malformedAsOfRejected(String label, String value) {
        String code = newAccount("ASSET");

        String detail = assertBadRequest(asOf(code, value));

        assertNames(detail, "as_of");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"2000 fractional digits", "2000 characters of garbage"})
    @DisplayName("REQ: as-of-rfc3339 — a very long as_of is 400 problem+json naming as_of, never 500")
    void longAsOfRejected(String what) {
        String code = newAccount("ASSET");
        String value = what.startsWith("2000 fractional") ? "2026-10-21T15:00:00." + "1".repeat(2000) + "Z" : "x".repeat(2000);

        assertNames(assertBadRequest(asOf(code, value)), "as_of");
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "a real leap second | 2016-12-31T23:59:60Z",
            "unknown local offset -00:00 | 2026-10-21T15:00:00-00:00",
            "year 0000 | 0000-01-01T00:00:00Z",
            "year 0001 at +18:00, year 0 in UTC | 0001-01-01T00:00:00%2B18:00"})
    @DisplayName("REQ: as-of-rfc3339 — a value the spec's grammar admits but RFC 3339 or the calendar make doubtful is answered 200 or refused 400 problem+json, never 500")
    void doubtfulValues(String label, String value) {
        String code = newAccount("ASSET");

        RawResponse r = asOf(code, value);

        observe("doubtful as_of (" + label + ") " + value, r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isIn(200, 400);
        if (r.status() == 400) {
            assertBadRequest(r);
        } else {
            Map<String, Object> body = assertBalanceBody(r, code);
            echoedAsOf(body, r.body());
            assertThat(integer(body.get("balance_cents"), "balance_cents")).isZero();
        }
    }

    @ParameterizedTest(name = "[{index}] ?{0}")
    @ValueSource(strings = {"as_of=", "as_of"})
    @DisplayName("REQ: as-of-rfc3339 — an empty as_of (as_of= or a bare as_of) is 400 problem+json, not the current balance")
    void emptyAsOfRejected(String query) {
        String code = newAccount("ASSET");

        RawResponse r = balanceQuery(code, query);

        observe("empty as_of ?" + query, r.status(), r.contentType(), r.body());
        assertBadRequest(r);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "the same value twice | as_of=2026-10-21T15:00:00Z&as_of=2026-10-21T15:00:00Z",
            "two different values | as_of=2026-10-21T15:00:00Z&as_of=2026-10-20T15:00:00Z",
            "one instant spelled two ways | as_of=2026-10-21T15:00:00Z&as_of=2026-10-21T12:00:00-03:00",
            "a value then an empty one | as_of=2026-10-21T15:00:00Z&as_of=",
            "an empty one then a value | as_of=&as_of=2026-10-21T15:00:00Z",
            "twice empty | as_of=&as_of=",
            "a value then a bare name | as_of=2026-10-21T15:00:00Z&as_of",
            "three times | as_of=2026-10-21T15:00:00Z&as_of=2026-10-21T15:00:00Z&as_of=2026-10-21T15:00:00Z"})
    @DisplayName("REQ: as-of-rfc3339 — as_of given more than once is 400 problem+json, whatever the values")
    void repeatedAsOfRejected(String label, String query) {
        String code = newAccount("ASSET");

        RawResponse r = balanceQuery(code, query);

        observe("repeated as_of (" + label + ")", r.status(), r.contentType(), r.body());
        assertBadRequest(r);
    }

    @Test
    @DisplayName("REQ: as-of-rfc3339 — a literal '+' in a positive offset (which a query string decodes as a space) is 400 problem+json")
    void literalPlusRejected() {
        String code = newAccount("ASSET");

        RawResponse r = asOf(code, "2026-10-21T18:00:00+03:00");

        observe("literal + in as_of", r.status(), r.contentType(), r.body());
        assertBadRequest(r);
    }

    @Test
    @DisplayName("REQ: as-of-rfc3339 — the same positive offset written %2B is accepted: 2026-10-21T18:00:00%2B03:00 reads as of 2026-10-21T15:00:00Z")
    void encodedPlusAccepted() {
        assertSettled(SPEC_INSTANT);
        String code = newAccount("ASSET");

        assertAsOfBody(asOf(code, "2026-10-21T18:00:00%2B03:00"), code, SPEC_INSTANT);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "%zz as the whole value | as_of=%zz",
            "a lone % | as_of=%",
            "an incomplete %2 | as_of=%2",
            "a trailing % after a valid instant | as_of=2026-10-21T15:00:00Z%",
            "%zz after a valid instant | as_of=2026-10-21T15:00:00Z%zz",
            "the invalid UTF-8 byte %E9 | as_of=%E9",
            "the invalid UTF-8 byte %FF | as_of=2026-10-21T15:00:00Z%FF",
            "the invalid UTF-8 byte %C0 | as_of=2026-10-21T15:00:00%C0"})
    @DisplayName("REQ: as-of-rfc3339 — a malformed percent-encoding in as_of (%zz, a lone or incomplete %, an invalid UTF-8 byte) is 400 problem+json whose detail is the fixed statement that the query string is not valid percent-encoded UTF-8 and repeats nothing sent; never 500")
    void malformedEncodingRejected(String label, String query) {
        String code = newAccount("ASSET");

        assertBadEncodingDetail(assertBadRequest(balanceQuery(code, query)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "invalid continuation byte | %C3%28",
            "overlong encoding of '/' | %C0%AF",
            "UTF-16 surrogate | %ED%A0%80",
            "beyond U+10FFFF | %F4%90%80%80",
            "truncated three-byte sequence | %E2%82"})
    @DisplayName("REQ: as-of-rfc3339 — an invalid multi-byte UTF-8 sequence in as_of is 400 problem+json (detail: the encoding statement or naming as_of), never 500")
    void invalidUtf8SequenceRejected(String label, String value) {
        String code = newAccount("ASSET");

        RawResponse r = asOf(code, value);

        observe("invalid UTF-8 in as_of (" + label + ")", r.status(), r.contentType(), r.body());
        String detail = assertBadRequest(r);
        if (!isBadEncodingDetail(detail)) assertNames(detail, "as_of");
    }

    // ------------------------------------------------------------------ as-of-only-parameter

    @Test
    @DisplayName("REQ: as-of-only-parameter — asOf with a valid instant is 400 problem+json naming asOf, not 200 with the current balance")
    void misspelledAsOfRejected() {
        String code = newAccount("ASSET");

        assertNames(assertBadRequest(balanceQuery(code, "asOf=" + SPEC_AS_OF)), "asOf");
    }

    @Test
    @DisplayName("REQ: as-of-only-parameter — AS_OF with a valid instant is 400 problem+json naming AS_OF")
    void upperCasedAsOfRejected() {
        String code = newAccount("ASSET");

        assertNames(assertBadRequest(balanceQuery(code, "AS_OF=" + SPEC_AS_OF)), "AS_OF");
    }

    @ParameterizedTest(name = "[{index}] ?{0}")
    @ValueSource(strings = {"as_of=" + SPEC_AS_OF + "&x=1", "x=1&as_of=" + SPEC_AS_OF})
    @DisplayName("REQ: as-of-only-parameter — x=1 next to a valid as_of, in either order, is 400 problem+json naming x")
    void extraParameterRejected(String query) {
        String code = newAccount("ASSET");

        assertNames(assertBadRequest(balanceQuery(code, query)), "x");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "As_Of | As_Of",
            "asof | asof",
            "as-of | as-of",
            "as_of_ | as_of_",
            "_as_of | _as_of",
            "AsOf | AsOf",
            "asOF | asOF",
            "as.of | as.of",
            "as_of[] | as_of%5B%5D"})
    @DisplayName("REQ: as-of-only-parameter — a near-miss of as_of (other case, dash, dot, a character missing or extra, a [] suffix) with a valid instant is 400 problem+json naming it")
    void nearMissNameRejected(String name, String encodedName) {
        String code = newAccount("ASSET");

        assertNames(assertBadRequest(balanceQuery(code, encodedName + "=" + SPEC_AS_OF)), name);
    }

    @ParameterizedTest(name = "[{index}] ?{0}=...")
    @ValueSource(strings = {"%20as_of", "as_of%20", "as_of%09"})
    @DisplayName("REQ: as-of-only-parameter — as_of with whitespace around its name is not as_of: 400 problem+json, not an as-of or current balance")
    void paddedNameRejected(String encodedName) {
        String code = newAccount("ASSET");

        RawResponse r = balanceQuery(code, encodedName + "=" + SPEC_AS_OF);

        observe("padded parameter name ?" + encodedName, r.status(), r.contentType(), r.body());
        assertBadRequest(r);
    }

    @ParameterizedTest(name = "[{index}] ?{0}")
    @ValueSource(strings = {"x=1", "x=", "x", "x=%20", "x=" + SPEC_AS_OF, "foo=bar"})
    @DisplayName("REQ: as-of-only-parameter — an unknown parameter on a current-balance request (no as_of), whatever its value or none, is 400 problem+json naming it")
    void unknownParameterWithoutAsOfRejected(String query) {
        String code = newAccount("ASSET");
        String name = query.contains("=") ? query.substring(0, query.indexOf('=')) : query;

        assertNames(assertBadRequest(balanceQuery(code, query)), name);
    }

    @Test
    @DisplayName("REQ: as-of-only-parameter — every unknown parameter is named: x, y and zeta_unknown next to a valid as_of")
    void everyUnknownParameterNamed() {
        String code = newAccount("ASSET");

        String detail = assertBadRequest(balanceQuery(code, "x=1&as_of=" + SPEC_AS_OF + "&y=2&zeta_unknown=3"));

        assertNames(detail, "x");
        assertNames(detail, "y");
        assertNames(detail, "zeta_unknown");
    }

    @Test
    @DisplayName("REQ: as-of-only-parameter — an unknown parameter next to a malformed as_of is 400 problem+json")
    void unknownParameterWithMalformedAsOf() {
        String code = newAccount("ASSET");

        RawResponse r = balanceQuery(code, "as_of=2026-10-21&x=1");

        observe("unknown parameter with a malformed as_of", r.status(), r.contentType(), r.body());
        assertBadRequest(r);
    }

    @ParameterizedTest(name = "[{index}] ?{0}")
    @ValueSource(strings = {"x=%zz", "x=%zz&as_of=" + SPEC_AS_OF, "as_of=" + SPEC_AS_OF + "&x=%zz", "%zz=1", "x=%", "as_of=" + SPEC_AS_OF + "&x=%E9"})
    @DisplayName("REQ: as-of-only-parameter — an unknown parameter with a malformed percent-encoding (x=%zz and variants) is 400 problem+json whose detail is the fixed statement that the query string is not valid percent-encoded UTF-8; never 500")
    void unknownParameterMalformedEncoding(String query) {
        String code = newAccount("ASSET");

        assertBadEncodingDetail(assertBadRequest(balanceQuery(code, query)));
    }

    @ParameterizedTest(name = "[{index}] ''?{0}''")
    @ValueSource(strings = {"", "&", "&&", "=1", "&as_of=" + SPEC_AS_OF, "as_of=" + SPEC_AS_OF + "&"})
    @DisplayName("REQ: as-of-only-parameter — a query string with empty segments or an empty name is answered 200 or refused 400 problem+json, never 500")
    void emptyQuerySegments(String query) {
        String code = newAccount("ASSET");

        RawResponse r = balanceQuery(code, query);

        observe("query '?" + query + "'", r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isIn(200, 400);
        if (r.status() == 400) {
            assertBadRequest(r);
        } else {
            assertBalanceBody(r, code);
        }
    }

    @ParameterizedTest(name = "[{index}] ?{0}=...")
    @ValueSource(strings = {"as%5Fof", "%61s_of", "%61%73%5F%6F%66"})
    @DisplayName("REQ: as-of-only-parameter — a percent-encoded spelling of the name as_of is either read as as_of (200 as of that instant) or refused 400 problem+json, never 500")
    void percentEncodedName(String encodedName) {
        assertSettled(SPEC_INSTANT);
        String code = newAccount("ASSET");

        RawResponse r = balanceQuery(code, encodedName + "=" + SPEC_AS_OF);

        observe("percent-encoded name ?" + encodedName, r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isIn(200, 400);
        if (r.status() == 400) {
            assertBadRequest(r);
        } else {
            assertAsOfBody(r, code, SPEC_INSTANT);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "JSON body carrying as_of | application/json | {\"as_of\":\"2020-01-01T00:00:00Z\"}",
            "form body carrying as_of | application/x-www-form-urlencoded | as_of=2020-01-01T00:00:00Z",
            "form body carrying x | application/x-www-form-urlencoded | x=1",
            "malformed JSON body | application/json | {",
            "text/plain body | text/plain | as_of=2020-01-01T00:00:00Z"})
    @DisplayName("REQ: as-of-only-parameter — a body sent with a balance GET is never read as a parameter: 200 with the exact current-balance body (as_of null) or a 4xx problem+json, never 500")
    void bodyIsNotAParameter(String label, String contentType, String body) {
        String code = newAccount("ASSET");
        String other = newAccount("LIABILITY");
        postOk(entry(line(code, 25), line(other, -25)));

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "GET /api/v1/accounts/" + code + "/balance HTTP/1.1\r\n"
                + "Host: localhost:" + RestAssured.port + "\r\n"
                + "Accept: */*\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";

        RawResponse r = rawExchange(concat(latin1(head), bytes));

        observe("balance GET with a " + label, r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isLessThan(500);
        if (r.status() == 200) {
            Map<String, Object> current = assertBalanceBody(r, code);
            assertThat(current.get("as_of")).as("as_of must stay null: the body is not a parameter").isNull();
            assertThat(integer(current.get("balance_cents"), "balance_cents")).isEqualTo(BigInteger.valueOf(25));
        } else {
            assertThat(r.status()).as("status").isBetween(400, 499);
            assertRawProblem(r, r.status());
        }
    }

    @Test
    @DisplayName("REQ: as-of-only-parameter — a malformed as_of with Accept: application/xml is a 400 or 406 that still carries a body, never 500")
    void errorWithXmlAccept() {
        String code = newAccount("ASSET");

        RawResponse r = rawGet("/accounts/" + code + "/balance?as_of=2026-10-21", "application/xml");

        observe("malformed as_of with Accept xml", r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isIn(400, 406);
        assertThat(r.body()).as("error must carry a body").isNotBlank();
    }

    // ------------------------------------------------------------------ settled-instants-only

    @Test
    @DisplayName("REQ: settled-instants-only — as of 61 seconds before the database's clock is 200 with the sum of the lines recorded at or before it")
    void sixtyOneSecondsAnswered() {
        Timeline t = timeline();
        Instant instant = dbNow().minusSeconds(61);
        assertThat(t.expected(t.hist, instant)).as("precondition: every fixture line is older").isEqualTo(BigInteger.valueOf(1150));

        assertThat(balanceAt(t.hist, instant)).isEqualTo(BigInteger.valueOf(1150));
    }

    @Test
    @DisplayName("REQ: settled-instants-only — as of exactly 60 seconds before the database's clock (the created_at of an entry posted just before) is 200")
    void exactlyOneWindowAnswered() {
        Timeline t = timeline();
        Instant instant = dbNow().minus(WINDOW);

        assertThat(balanceAt(t.hist, instant)).isEqualTo(t.expected(t.hist, instant));
    }

    @ParameterizedTest(name = "{0} s before the database's clock")
    @ValueSource(ints = {59, 30, 1, 0})
    @DisplayName("REQ: settled-instants-only — an as_of inside the window (59, 30, 1, 0 seconds before the database's clock) is 422 as-of-not-settled whose detail states the 60-second window")
    void insideWindowRefused(int seconds) {
        String code = newAccount("ASSET");
        Instant instant = dbNow().minusSeconds(seconds);

        String detail = assertNotSettled(asOf(code, utc(instant, 6)));

        assertThat(detail).as("the detail must state the 60-second window").containsPattern("(?<![0-9])60(?![0-9])");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "one second after the database's clock | +1",
            "one hour after | +3600",
            "one year after | +31536000",
            "the last microsecond of 9999 | 9999-12-31T23:59:59.999999Z",
            "the end of 9999 at -18:00, year 10000 in UTC | 9999-12-31T23:59:59-18:00"})
    @DisplayName("REQ: settled-instants-only — a future as_of is 422 as-of-not-settled, never 500")
    void futureRefused(String label, String when) {
        String code = newAccount("ASSET");
        String value = when.startsWith("+") ? utc(dbNow().plusSeconds(Long.parseLong(when.substring(1))), 6) : when;

        assertNotSettled(asOf(code, value));
    }

    @Test
    @DisplayName("REQ: settled-instants-only — a new posting is in the current balance at once (no window applies without as_of) but not in the balance as of 61 seconds before the database's clock")
    void newPostingInCurrentNotInAsOf() {
        Timeline t = timeline();
        BigInteger before = currentOf(t.recent);

        Response posted = postOk(entry(line(t.recent, 55), line(t.recentCounter, -55)));
        Instant instant = createdAt(posted).minusSeconds(61);

        assertThat(currentOf(t.recent)).as("current balance includes the new entry").isEqualTo(before.add(BigInteger.valueOf(55)));
        assertThat(t.expected(t.recent, instant)).as("precondition: the settled fixture line").isEqualTo(BigInteger.valueOf(400));
        assertThat(balanceAt(t.recent, instant)).as("as-of balance excludes the new entry").isEqualTo(BigInteger.valueOf(400));
    }

    // ------------------------------------------------------------------ balance-error-order

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "date only | as_of=2026-10-21",
            "empty | as_of=",
            "repeated | as_of=2026-10-21T15:00:00Z&as_of=2026-10-21T15:00:00Z",
            "malformed percent-encoding | as_of=%zz"})
    @DisplayName("REQ: balance-error-order — an unregistered code with a malformed as_of is 400, not 404")
    void malformedAsOfBeforeUnknownAccount(String label, String query) {
        assertBadRequest(balanceQuery(uniqueCode(), query));
    }

    @ParameterizedTest(name = "[{index}] ?{0}")
    @ValueSource(strings = {"x=1", "asOf=" + SPEC_AS_OF, "as_of=" + SPEC_AS_OF + "&x=1"})
    @DisplayName("REQ: balance-error-order — an unregistered code with a query parameter other than as_of is 400, not 404")
    void unknownParameterBeforeUnknownAccount(String query) {
        assertBadRequest(balanceQuery(uniqueCode(), query));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"30 seconds before the database's clock", "one hour after the database's clock"})
    @DisplayName("REQ: balance-error-order — an unregistered code with a well-formed as_of that is not settled is 404, not 422")
    void unknownAccountBeforeUnsettled(String when) {
        Instant now = dbNow();
        Instant instant = when.startsWith("30") ? now.minusSeconds(30) : now.plusSeconds(3600);

        RawResponse r = asOf(uniqueCode(), utc(instant, 6));

        assertRawProblem(r, 404);
        assertRawDefaultType(r);
    }

    @Test
    @DisplayName("REQ: balance-error-order — an unregistered code with a settled as_of is 404, not a zero balance")
    void unknownAccountSettledIs404() {
        assertSettled(SPEC_INSTANT);

        RawResponse r = asOf(uniqueCode(), SPEC_AS_OF);

        assertRawProblem(r, 404);
        assertRawDefaultType(r);
    }

    @Test
    @DisplayName("REQ: balance-error-order — a registered code with an unknown parameter and an as_of inside the window is 400 naming the parameter, not 422")
    void unknownParameterBeforeUnsettled() {
        String code = newAccount("ASSET");
        Instant instant = dbNow().minusSeconds(30);

        String detail = assertBadRequest(balanceQuery(code, "as_of=" + utc(instant, 6) + "&x=1"));

        assertNames(detail, "x");
    }

    @Test
    @DisplayName("REQ: balance-error-order — an unregistered code with a malformed as_of and an unknown parameter is 400, not 404")
    void bothBadRequestsBeforeUnknownAccount() {
        assertBadRequest(balanceQuery(uniqueCode(), "as_of=2026-10-21&x=1"));
    }

    @ParameterizedTest(name = "[{index}] code {0}")
    @ValueSource(strings = {"UPPER", "9digit", "a:b:c"})
    @DisplayName("REQ: balance-error-order — a malformed (so unregistered) code with an as_of inside the window is a 400 or 404 problem+json, never 422")
    void malformedCodeBeforeUnsettled(String code) {
        Instant instant = dbNow().minusSeconds(30);

        RawResponse r = asOf(code, utc(instant, 6));

        observe("malformed code " + code + " with an unsettled as_of", r.status(), r.contentType(), r.body());
        assertThat(r.status()).as("status (body %s)", r.body()).isIn(400, 404);
        assertRawProblem(r, r.status());
        assertRawDefaultType(r);
    }

    // ------------------------------------------------------------------ settled-answer-final

    @Test
    @DisplayName("REQ: settled-answer-final — against the suite's database (bound expected in place, prepared transactions expected disabled) the application starts and serves registration, posting, current and as-of balances")
    void startsAndServes() {
        assertSettled(SPEC_INSTANT);
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        postOk(entry(line(a, 10), line(b, -10)));

        assertThat(integer(assertBalanceBody(balanceQuery(a, null), a).get("balance_cents"), "balance_cents"))
                .isEqualTo(BigInteger.TEN);
        assertThat(assertAsOfBody(asOf(a, SPEC_AS_OF), a, SPEC_INSTANT)).isZero();
    }

    // ------------------------------------------------------------------ unknown-query-parameters (PROJECT-CONVENTIONS.md)

    @Test
    @DisplayName("REQ: unknown-query-parameters — GET /accounts/{code}?x=1 is 400 problem+json naming x")
    void accountLookupUnknownParameter() {
        String code = newAccount("ASSET");

        Response r = given().queryParam("x", "1").when().get("/accounts/{code}", code);

        observe("GET /accounts/{code}?x=1", r);
        assertNames(assertProblem(r, 400), "x");
    }

    @Test
    @DisplayName("REQ: unknown-query-parameters — GET /accounts/{code}?as_of=<settled instant> is 400 problem+json naming as_of: the account lookup accepts no parameter")
    void accountLookupAsOf() {
        String code = newAccount("ASSET");

        Response r = given().queryParam("as_of", SPEC_AS_OF).when().get("/accounts/{code}", code);

        observe("GET /accounts/{code}?as_of=...", r);
        assertNames(assertProblem(r, 400), "as_of");
    }

    @Test
    @DisplayName("REQ: unknown-query-parameters — POST /accounts?x=1 with a valid body is 400 problem+json naming x and registers nothing")
    void registrationUnknownParameter() {
        String code = uniqueCode();

        Response r = given().contentType(ContentType.JSON).queryParam("x", "1").body(json("code", code, "type", "ASSET"))
                .when().post("/accounts");

        observe("POST /accounts?x=1", r);
        assertNames(assertProblem(r, 400), "x");
        assertProblem(given().when().get("/accounts/{code}", code), 404);
    }

    @Test
    @DisplayName("REQ: unknown-query-parameters — POST /journal-entries?x=1 with a valid entry is 400 problem+json naming x and posts nothing")
    void postingUnknownParameter() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");

        Response r = given().contentType(ContentType.JSON).header(IDEMPOTENCY_KEY, newKey()).queryParam("x", "1")
                .body(entry(line(a, 100), line(b, -100))).when().post(ENTRIES);

        observe("POST /journal-entries?x=1", r);
        assertNames(assertProblem(r, 400), "x");
        assertUntouched(a, b);
    }

    @Test
    @DisplayName("REQ: unknown-query-parameters — POST /journal-entries/{entry_id}/reversal?x=1 is 400 problem+json naming x and does not reverse the entry")
    void reversalUnknownParameter() {
        String a = newAccount("ASSET");
        String b = newAccount("LIABILITY");
        String id = postOk(entry(line(a, 100), line(b, -100))).jsonPath().getString("entry_id");

        Response r = given().header(IDEMPOTENCY_KEY, newKey()).queryParam("x", "1")
                .when().post(ENTRIES + "/{entry_id}/reversal", id);

        observe("POST /journal-entries/{entry_id}/reversal?x=1", r);
        assertNames(assertProblem(r, 400), "x");
        assertBalance(a, 100);
        assertBalance(b, -100);
    }

    // ------------------------------------------------------------------ fixture: settled history

    /** One fixture line: the account, its amount and its entry's created_at. */
    record Line(String account, long amount, Instant at) {}

    /** Settled history shared by the scenarios that need it; built once per JVM by {@link #timeline()}. */
    static final class Timeline {
        final List<Line> lines = new ArrayList<>();
        /** Three entries on hist / histCounter: +1000, +200, -50 on hist, in posting order. */
        String hist;
        String histCounter;
        Instant histRegistered;
        final List<Instant> histAt = new ArrayList<>();
        final List<String> histRaw = new ArrayList<>();
        /** +700 on rev, then its reversal. */
        String rev;
        String revCounter;
        Instant revOriginalAt;
        Instant revReversalAt;
        /** +300 on late, reserved for the scenario that posts to it later. */
        String late;
        String lateCounter;
        Instant lateAt;
        String lateEntryId;
        /** +400 on recent, reserved for the scenario that posts a new entry to it. */
        String recent;
        String recentCounter;
        /** Registered, never posted to. */
        String empty;
        /** Sixteen simultaneous entries of 2^i on conc: every as-of sum names exactly which were included. */
        String conc;
        String concCounter;
        final List<Instant> concAt = new ArrayList<>();

        BigInteger expected(String account, Instant asOf) {
            long sum = 0;
            for (Line l : lines) {
                if (l.account().equals(account) && !l.at().isAfter(asOf)) sum += l.amount();
            }
            return BigInteger.valueOf(sum);
        }

        Instant last() {
            return lines.stream().map(Line::at).max(Comparator.naturalOrder()).orElseThrow();
        }
    }

    Timeline timeline() {
        if (timeline != null) return timeline;
        if (timelineFailure != null) throw timelineFailure;
        try {
            timeline = buildTimeline();
            return timeline;
        } catch (RuntimeException | AssertionError e) {
            timelineFailure = new IllegalStateException("building the settled-history fixture failed", e);
            throw timelineFailure;
        }
    }

    private Timeline buildTimeline() {
        Timeline t = new Timeline();
        t.hist = newAccount("ASSET");
        t.histCounter = newAccount("LIABILITY");
        t.histRegistered = registeredAt(t.hist);
        for (long amount : new long[] {1000, 200, -50}) {
            Response r = postAndRecord(t, t.hist, t.histCounter, amount);
            t.histAt.add(createdAt(r));
            t.histRaw.add(r.jsonPath().getString("created_at"));
            pause();
        }
        observe("entry created_at as returned", 201, "", String.join(", ", t.histRaw));

        t.rev = newAccount("ASSET");
        t.revCounter = newAccount("LIABILITY");
        Response original = postAndRecord(t, t.rev, t.revCounter, 700);
        t.revOriginalAt = createdAt(original);
        pause();
        Response reversal = given().header(IDEMPOTENCY_KEY, newKey()).when()
                .post(ENTRIES + "/{entry_id}/reversal", original.jsonPath().getString("entry_id"));
        assertThat(reversal.statusCode()).as("precondition: reversal should be 201: %s", reversal.asString()).isEqualTo(201);
        t.revReversalAt = createdAt(reversal);
        t.lines.add(new Line(t.rev, -700, t.revReversalAt));
        t.lines.add(new Line(t.revCounter, 700, t.revReversalAt));
        pause();

        t.late = newAccount("ASSET");
        t.lateCounter = newAccount("LIABILITY");
        Response late = postAndRecord(t, t.late, t.lateCounter, 300);
        t.lateAt = createdAt(late);
        t.lateEntryId = late.jsonPath().getString("entry_id");

        t.recent = newAccount("ASSET");
        t.recentCounter = newAccount("LIABILITY");
        postAndRecord(t, t.recent, t.recentCounter, 400);

        t.empty = newAccount("REVENUE");

        t.conc = newAccount("ASSET");
        t.concCounter = newAccount("EXPENSE");
        List<Response> batch = simultaneously(16, i -> post(newKey(),
                entry(line(t.conc, 1L << i), line(t.concCounter, -(1L << i)))));
        for (int i = 0; i < batch.size(); i++) {
            Response r = batch.get(i);
            assertThat(r.statusCode()).as("precondition: simultaneous post %d should be 201: %s", i, r.asString()).isEqualTo(201);
            Instant at = createdAt(r);
            t.concAt.add(at);
            t.lines.add(new Line(t.conc, 1L << i, at));
            t.lines.add(new Line(t.concCounter, -(1L << i), at));
        }

        for (Line l : t.lines) {
            assertThat(l.at().getNano() % 1000).as("precondition: created_at %s has at most microsecond digits", l.at()).isZero();
        }
        for (int i = 1; i < t.histAt.size(); i++) {
            assertThat(Duration.between(t.histAt.get(i - 1), t.histAt.get(i)).toNanos())
                    .as("precondition: sequential entries are recorded at least 2 microseconds apart").isGreaterThanOrEqualTo(2_000);
        }
        assertThat(Duration.between(t.revOriginalAt, t.revReversalAt).toNanos())
                .as("precondition: the reversal is recorded at least 2 microseconds after the original").isGreaterThanOrEqualTo(2_000);

        // Two extra seconds, so that whole-second roundings up of fixture instants are settled too.
        awaitSettled(t.last().plusSeconds(2));
        return t;
    }

    /** Posts [amount, -amount] on (debit, credit) and records both lines. */
    private Response postAndRecord(Timeline t, String debit, String credit, long amount) {
        Response r = postOk(entry(line(debit, amount), line(credit, -amount)));
        Instant at = createdAt(r);
        t.lines.add(new Line(debit, amount, at));
        t.lines.add(new Line(credit, -amount, at));
        return r;
    }

    // ------------------------------------------------------------------ the database's clock

    /** The database's clock: the created_at of an entry posted just now on two dedicated accounts. */
    Instant dbNow() {
        if (clockDebit == null) {
            clockDebit = newAccount("ASSET");
            clockCredit = newAccount("LIABILITY");
        }
        Instant jvm = Instant.now();
        Instant db = createdAt(postOk(entry(line(clockDebit, 1), line(clockCredit, -1))));
        if (!clockObserved) {
            clockObserved = true;
            observe("database clock minus JVM clock", 201, "", Duration.between(jvm, db).toString());
        }
        return db;
    }

    /** Waits until {@code instant} is at least one window old on the database's clock. */
    void awaitSettled(Instant instant) {
        Instant giveUp = Instant.now().plus(Duration.ofMinutes(3));
        while (true) {
            Duration left = Duration.between(dbNow().minus(WINDOW), instant);
            if (left.isNegative()) return;
            assertThat(Instant.now()).as("waiting for %s to settle took too long", instant).isBefore(giveUp);
            sleep(Math.min(left.toMillis() + 100, 15_000));
        }
    }

    /** Precondition for tests that use a fixed instant: it must already be settled. */
    void assertSettled(Instant instant) {
        assertThat(dbNow().minus(WINDOW)).as("precondition: %s must be settled on the database's clock", instant).isAfter(instant);
    }

    static void pause() {
        sleep(20);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ requests

    /** GET {@code pathAndQuery} under /api/v1 over a raw socket, written verbatim (percent-encode it yourself). */
    RawResponse rawGet(String pathAndQuery) {
        return rawGet(pathAndQuery, "*/*");
    }

    RawResponse rawGet(String pathAndQuery, String accept) {
        String head = "GET /api/v1" + pathAndQuery + " HTTP/1.1\r\n"
                + "Host: localhost:" + RestAssured.port + "\r\n"
                + "Accept: " + accept + "\r\n"
                + "Connection: close\r\n\r\n";
        return rawExchange(latin1(head));
    }

    /** The balance of {@code code} with {@code query} after the '?' exactly as given; null sends no '?'. */
    RawResponse balanceQuery(String code, String query) {
        return rawGet("/accounts/" + code + "/balance" + (query == null ? "" : "?" + query));
    }

    /** The balance of {@code code} with {@code as_of=value}, the value exactly as given. */
    RawResponse asOf(String code, String value) {
        return balanceQuery(code, "as_of=" + value);
    }

    /** The as-of balance of {@code code} at {@code instant}, sent in UTC with six fractional digits. */
    BigInteger balanceAt(String code, Instant instant) {
        return assertAsOfBody(asOf(code, utc(instant, 6)), code, instant);
    }

    BigInteger currentOf(String code) {
        Map<String, Object> body = assertBalanceBody(balanceQuery(code, null), code);
        assertThat(body.get("as_of")).as("as_of of a current balance").isNull();
        return integer(body.get("balance_cents"), "balance_cents");
    }

    String accountCurrency(String code) {
        return lookup(code).getString("currency");
    }

    Instant registeredAt(String code) {
        return instant(lookup(code).getString("created_at"), "account created_at");
    }

    private JsonPath lookup(String code) {
        Response r = given().when().get("/accounts/{code}", code);
        assertThat(r.statusCode()).as("precondition: lookup of %s should be 200: %s", code, r.asString()).isEqualTo(200);
        return r.jsonPath();
    }

    /** Sends {@code n} requests at the same moment; responses come back in index order. */
    static List<Response> simultaneously(int n, IntFunction<Response> request) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.apply(index);
                }));
            }
            ready.await();
            start.countDown();
            List<Response> out = new ArrayList<>();
            for (Future<Response> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } catch (Exception e) {
            throw new AssertionError("simultaneous requests failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ assertions

    /** A 200 balance: application/json with exactly the four members, the requested account_code, a currency and an integer balance. */
    static Map<String, Object> assertBalanceBody(RawResponse r, String code) {
        assertThat(r.status()).as("status (Content-Type %s, body %s)", r.contentType(), r.body()).isEqualTo(200);
        assertThat(r.contentType()).as("Content-Type of a balance").startsWith("application/json");
        Map<String, Object> body = JsonPath.from(r.body()).getMap("");
        assertThat(body).as("balance body (%s)", r.body()).isNotNull();
        assertThat(body.keySet()).as("members of a balance body (%s)", r.body()).containsExactlyInAnyOrderElementsOf(BALANCE_KEYS);
        assertThat(body.get("account_code")).as("account_code").isEqualTo(code);
        assertThat(body.get("currency")).as("currency (body %s)", r.body()).isInstanceOf(String.class);
        integer(body.get("balance_cents"), "balance_cents (body " + r.body() + ")");
        return body;
    }

    /** A 200 as-of balance whose as_of is {@code requested} in UTC; returns balance_cents. */
    static BigInteger assertAsOfBody(RawResponse r, String code, Instant requested) {
        Map<String, Object> body = assertBalanceBody(r, code);
        assertThat(echoedAsOf(body, r.body())).as("as_of must be the requested instant (body %s)", r.body()).isEqualTo(requested);
        return integer(body.get("balance_cents"), "balance_cents");
    }

    static Instant echoedAsOf(Map<String, Object> body, String raw) {
        Object value = body.get("as_of");
        assertThat(value).as("as_of of an as-of balance must be a string (body %s)", raw).isInstanceOf(String.class);
        OffsetDateTime t;
        try {
            t = OffsetDateTime.parse((String) value);
        } catch (DateTimeParseException e) {
            throw new AssertionError("as_of '" + value + "' is not an RFC 3339 date-time", e);
        }
        assertThat(t.getOffset()).as("as_of '%s' must be expressed in UTC", value).isEqualTo(ZoneOffset.UTC);
        return t.toInstant();
    }

    /** Every spelling is a 200 as of {@code instant}, with a body equal to the first spelling's. */
    void assertIdenticalAnswers(String code, Instant instant, List<String> spellings) {
        RawResponse reference = asOf(code, spellings.get(0));
        assertAsOfBody(reference, code, instant);
        observe("reference answer for " + spellings.get(0), reference.status(), reference.contentType(), reference.body());
        Map<String, Object> expected = JsonPath.from(reference.body()).getMap("");
        for (String spelling : spellings.subList(1, spellings.size())) {
            RawResponse r = asOf(code, spelling);
            assertAsOfBody(r, code, instant);
            assertThat(JsonPath.from(r.body()).getMap("")).as("answer to %s, compared with %s", spelling, spellings.get(0))
                    .isEqualTo(expected);
        }
    }

    /** A 400 problem+json with the default type; returns its detail. */
    String assertBadRequest(RawResponse r) {
        String detail = assertRawProblem(r, 400);
        assertRawDefaultType(r);
        return detail;
    }

    /** A 422 problem+json of type as-of-not-settled; returns its detail. */
    String assertNotSettled(RawResponse r) {
        String detail = assertRawProblem(r, 422);
        assertThat(JsonPath.from(r.body()).getString("type")).as("problem type (body %s)", r.body()).isEqualTo(NOT_SETTLED);
        return detail;
    }

    static void assertRawDefaultType(RawResponse r) {
        String type = JsonPath.from(r.body()).getString("type");
        assertThat(type == null || type.equals("about:blank"))
                .as("a %d keeps the default problem type, but type was '%s' (body %s)", r.status(), type, r.body())
                .isTrue();
    }

    /** The detail names {@code name} as a whole word (not as part of a longer identifier). */
    static void assertNames(String detail, String name) {
        assertThat(detail).as("the detail must name %s", name)
                .containsPattern(Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])"));
    }

    static boolean isBadEncodingDetail(String detail) {
        return detail.strip().replaceAll("\\.$", "").equalsIgnoreCase(BAD_ENCODING);
    }

    static void assertBadEncodingDetail(String detail) {
        assertThat(isBadEncodingDetail(detail))
                .as("the detail must be the one fixed string '%s', but was '%s'", BAD_ENCODING, detail)
                .isTrue();
    }

    // ------------------------------------------------------------------ instants

    static Instant createdAt(Response r) {
        return instant(r.jsonPath().getString("created_at"), "created_at (body " + r.asString() + ")");
    }

    static Instant instant(String text, String what) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new AssertionError(what + " '" + text + "' is not an ISO-8601 timestamp with offset", e);
        }
    }

    /** {@code instant} in UTC, written with a 'Z' and exactly {@code digits} fractional digits (truncated). */
    static String utc(Instant instant, int digits) {
        return at(instant, "Z", digits);
    }

    /** {@code instant} as local time at {@code offset} ("Z", "+05:30", "-03:00"), for a query string: '+' is sent as %2B. */
    static String at(Instant instant, String offset, int digits) {
        OffsetDateTime t = instant.atOffset(offset.equals("Z") ? ZoneOffset.UTC : ZoneOffset.of(offset));
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d",
                t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond()));
        if (digits > 0) sb.append('.').append(String.format(Locale.ROOT, "%09d", t.getNano()), 0, digits);
        return sb.append(offset).toString().replace("+", "%2B");
    }

    static Instant floor(Instant t, int digits) {
        long unit = BigInteger.TEN.pow(9 - digits).longValueExact();
        return Instant.ofEpochSecond(t.getEpochSecond(), t.getNano() - t.getNano() % unit);
    }

    static Instant ceilSecond(Instant t) {
        return t.getNano() == 0 ? t : t.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
    }

    /** The microsecond halfway between {@code a} and {@code b}. */
    static Instant midpoint(Instant a, Instant b) {
        long micros = (ChronoUnit.MICROS.between(Instant.EPOCH, a) + ChronoUnit.MICROS.between(Instant.EPOCH, b)) / 2;
        Instant mid = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
        assertThat(mid).as("precondition: a microsecond strictly between %s and %s", a, b).isAfter(a).isBefore(b);
        return mid;
    }

    static String percentEncodeAll(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) sb.append('%').append(String.format("%02X", b & 0xff));
        return sb.toString();
    }
}
