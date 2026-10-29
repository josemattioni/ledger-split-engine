package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * As-of balances on the real stack: controller, use case, adapter, a real database as {@code ledger_app}. Entries
 * the test needs in the past are committed by the owner with a backdated {@code created_at}; "now" is always the
 * database's, never the JVM's, because the settle window is measured on the database clock. Nothing waits.
 *
 * <p>Seeds on {@code seller} (each against {@code fee} with the opposite sign): +100 at now - 10 min, +50 at
 * now - 5 min, +25 at now - 2 min, all settled long before the test reads them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsOfBalanceApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ENTRIES = "/api/v1/journal-entries";

    @Autowired
    private MockMvc mockMvc;

    private String seller;
    private String fee;
    private Instant now;
    private Instant t1;
    private Instant t2;
    private Instant t3;

    @BeforeEach
    void seed() throws SQLException {
        seller = registerAccount("seller_payable", AccountType.LIABILITY);
        fee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        now = databaseNow();
        t1 = now.minus(Duration.ofMinutes(10));
        t2 = now.minus(Duration.ofMinutes(5));
        t3 = now.minus(Duration.ofMinutes(2));
        insert(t1, 100);
        insert(t2, 50);
        insert(t3, 25);
    }

    private static Instant databaseNow() {
        return ownerJdbc().queryForObject("SELECT now()", Timestamp.class).toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    private void insert(Instant createdAt, long sellerCents) throws SQLException {
        insertEntryAsOwner(
                uniqueIdempotencyKey(),
                createdAt,
                null,
                List.of(new EntryLine(1, seller, sellerCents), new EntryLine(2, fee, -sellerCents)));
    }

    private ResultActions balance(String code, String asOf) throws Exception {
        return asOf == null
                ? mockMvc.perform(get("/api/v1/accounts/{code}/balance", code))
                : mockMvc.perform(get(URI.create("/api/v1/accounts/" + code + "/balance?as_of="
                        + URLEncoder.encode(asOf, StandardCharsets.UTF_8))));
    }

    private String balanceBody(String asOf) throws Exception {
        return balance(seller, asOf).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void postThroughTheApi(long sellerCents) throws Exception {
        mockMvc.perform(post(ENTRIES)
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"account_code":"%s","amount_cents":%d},{"account_code":"%s","amount_cents":%d}]}
                                """
                                .formatted(seller, sellerCents, fee, -sellerCents)))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ the backdated seeds, through HTTP

    @Test
    void anInstantBetweenTheFirstTwoEntriesSeesOnlyTheFirst() throws Exception {
        String asOf = t1.plus(Duration.ofMinutes(1)).toString();

        balance(seller, asOf)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_code").value(seller))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.balance_cents").value(100))
                .andExpect(jsonPath("$.as_of").value(asOf));
        balance(fee, asOf).andExpect(status().isOk()).andExpect(jsonPath("$.balance_cents").value(-100));
    }

    @Test
    void anInstantBetweenTheLastTwoEntriesSeesTheFirstTwo() throws Exception {
        balance(seller, t2.plus(Duration.ofMinutes(1)).toString()).andExpect(jsonPath("$.balance_cents").value(150));
    }

    @Test
    void anInstantEqualToAnEntrysCreatedAtIncludesItAndOneMicrosecondEarlierDoesNot() throws Exception {
        balance(seller, t2.toString()).andExpect(jsonPath("$.balance_cents").value(150));
        balance(seller, t2.minus(1, ChronoUnit.MICROS).toString()).andExpect(jsonPath("$.balance_cents").value(100));
    }

    @Test
    void anInstantBeforeEveryEntryGivesZeroNotA404() throws Exception {
        balance(seller, now.minus(Duration.ofHours(1)).toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(0));
    }

    @Test
    void theCurrentBalanceStillSumsEverythingAndHasANullAsOf() throws Exception {
        balance(seller, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(175))
                .andExpect(jsonPath("$.as_of").value((Object) null));
    }

    @Test
    void anOffsetIsAnsweredWithTheSameInstantExpressedInUtc() throws Exception {
        Instant instant = t2.plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
        // The ISO formatter always prints the seconds; OffsetDateTime.toString() drops them when they are zero,
        // which would make this request malformed (seconds are required) one run in sixty.
        String inSaoPaulo = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.ofHours(-3)));

        balance(seller, inSaoPaulo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.as_of").value(instant.toString()))
                .andExpect(jsonPath("$.balance_cents").value(150));
    }

    // ------------------------------------------------------------------ the settle window

    @Test
    void aPostMadeThroughTheApiIsInTheCurrentBalanceButNotInAnAsOfSixtyOneSecondsAgo() throws Exception {
        postThroughTheApi(1000);

        balance(seller, null).andExpect(jsonPath("$.balance_cents").value(1175));
        balance(seller, databaseNow().minusSeconds(61).toString()).andExpect(jsonPath("$.balance_cents").value(175));
    }

    @Test
    void anAsOfFiftyNineSecondsAgoIsA422WithTheAsOfNotSettledType() throws Exception {
        balance(seller, databaseNow().minusSeconds(59).toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:as-of-not-settled"))
                .andExpect(jsonPath("$.detail", containsString("60 seconds")));
    }

    @Test
    void aFutureAsOfIsA422TooAndDoesNotAnswer() throws Exception {
        balance(seller, now.plus(Duration.ofHours(1)).toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:as-of-not-settled"));
    }

    // ------------------------------------------------------------------ the order of the errors

    @Test
    void anUnknownAccountWithAnUnsettledAsOfIsA404NotA422() throws Exception {
        balance(uniqueCode("no_such_account"), databaseNow().minusSeconds(10).toString())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("No account with code")));
    }

    @Test
    void anUnknownAccountWithAMalformedAsOfIsA400NotA404() throws Exception {
        balance(uniqueCode("no_such_account"), "yesterday")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("as_of")));
    }

    @Test
    void anUnknownAccountWithAnUnknownParameterIsA400NotA404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{code}/balance?x=1", uniqueCode("no_such_account")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("\"x\"")));
    }

    @Test
    void aMisspelledAsOfIsA400NotTheCurrentBalance() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{code}/balance?asOf={instant}", seller, t1.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("\"asOf\"")));
    }

    // ------------------------------------------------------------------ the answer for a settled instant does not change

    @Test
    void theSameSettledInstantGivesTheIdenticalBodyTwiceEvenAfterALaterPost() throws Exception {
        String asOf = t2.plus(Duration.ofMinutes(1)).toString();

        String before = balanceBody(asOf);
        postThroughTheApi(5000);
        String after = balanceBody(asOf);

        assertThat(after).as("as of a settled instant, whatever is posted afterwards").isEqualTo(before);
        balance(seller, null).andExpect(jsonPath("$.balance_cents").value(5175));
    }
}
