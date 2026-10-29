package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The query-parameter rule on every endpoint, through the real stack: a stray parameter is a 400 that names it, and
 * it is refused before anything is written, so the same request without it (and the same idempotency key) still
 * works afterwards. The string is in the URL because that is what the rule looks at; MockMvc's {@code .param()}
 * sets none.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnknownQueryParameterApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String ENTRIES = "/api/v1/journal-entries";

    @Autowired
    private MockMvc mockMvc;

    private String seller;
    private String fee;

    @BeforeEach
    void setUp() {
        seller = registerAccount("seller_payable", AccountType.LIABILITY);
        fee = registerAccount("acquirer_fee", AccountType.EXPENSE);
    }

    private String entryBody() {
        return """
                {"lines":[{"account_code":"%s","amount_cents":500},{"account_code":"%s","amount_cents":-500}]}
                """
                .formatted(seller, fee);
    }

    private void assertBadRequestNaming(ResultActions response, String name) throws Exception {
        response.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.detail", containsString("\"" + name + "\"")));
    }

    private void assertBalance(String code, long cents) throws Exception {
        mockMvc.perform(get(ACCOUNTS + "/" + code + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(cents));
    }

    // ------------------------------------------------------------------ registration

    @Test
    void registeringWithAStrayParameterIsRefusedAndRegistersNothing() throws Exception {
        String code = uniqueCode("seller_payable");
        String body = "{\"code\":\"" + code + "\",\"type\":\"LIABILITY\"}";

        assertBadRequestNaming(
                mockMvc.perform(post(ACCOUNTS + "?x=1").contentType("application/json").content(body)), "x");

        mockMvc.perform(get(ACCOUNTS + "/" + code)).andExpect(status().isNotFound());
        mockMvc.perform(post(ACCOUNTS).contentType("application/json").content(body)).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ the account lookup

    @Test
    void lookingAnAccountUpWithAStrayParameterIsRefusedNamingIt() throws Exception {
        assertBadRequestNaming(mockMvc.perform(get(ACCOUNTS + "/" + seller + "?x=1")), "x");
        mockMvc.perform(get(ACCOUNTS + "/" + seller)).andExpect(status().isOk());
    }

    @Test
    void anAsOfOnTheAccountLookupIsRefusedBecauseOnlyTheBalanceTakesOne() throws Exception {
        assertBadRequestNaming(
                mockMvc.perform(get(ACCOUNTS + "/" + seller + "?as_of=2026-10-21T15:00:00Z")), "as_of");
    }

    @Test
    void aStrayParameterIsReportedBeforeAnUnknownAccount() throws Exception {
        assertBadRequestNaming(mockMvc.perform(get(ACCOUNTS + "/" + uniqueCode("nobody") + "?x=1")), "x");
    }

    // ------------------------------------------------------------------ posting

    @Test
    void postingWithAStrayParameterIsRefusedPostsNothingAndLeavesTheKeyUnused() throws Exception {
        String key = uniqueIdempotencyKey();

        assertBadRequestNaming(
                mockMvc.perform(post(ENTRIES + "?x=1")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(entryBody())),
                "x");

        assertBalance(seller, 0);
        // Not a replay: the key was never consumed.
        mockMvc.perform(post(ENTRIES)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(entryBody()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertBalance(seller, 500);
    }

    @Test
    void anIdempotencyKeyInTheQueryStringIsNotAnIdempotencyKey() throws Exception {
        // The header is missing too; either 400 would do, but the stray parameter is the first thing found.
        assertBadRequestNaming(
                mockMvc.perform(post(ENTRIES + "?idempotency_key=" + uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(entryBody())),
                "idempotency_key");
        assertBalance(seller, 0);
    }

    // ------------------------------------------------------------------ reversing

    @Test
    void reversingWithAStrayParameterIsRefusedReversesNothingAndLeavesTheKeyUnused() throws Exception {
        String entryId = JsonPath.read(
                mockMvc.perform(post(ENTRIES)
                                .header("Idempotency-Key", uniqueIdempotencyKey())
                                .contentType("application/json")
                                .content(entryBody()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.entry_id");
        String key = uniqueIdempotencyKey();

        assertBadRequestNaming(
                mockMvc.perform(post(ENTRIES + "/" + entryId + "/reversal?x=1").header("Idempotency-Key", key)), "x");

        assertBalance(seller, 500);
        mockMvc.perform(post(ENTRIES + "/" + entryId + "/reversal").header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertBalance(seller, 0);
    }

    // ------------------------------------------------------------------ what the rule must not mask

    @Test
    void anUnknownPathWithAStrayParameterIsStillA404() throws Exception {
        mockMvc.perform(get("/api/v1/nothing-here?x=1"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("query parameter"))));
    }

    @Test
    void aMethodTheEndpointDoesNotHaveWithAStrayParameterIsStillA405() throws Exception {
        mockMvc.perform(put(ACCOUNTS + "/" + seller + "?x=1").contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(not(containsString("query parameter"))));
    }

    @Test
    void theBalanceEndpointStillAcceptsItsOwnParameterAndNothingElse() throws Exception {
        mockMvc.perform(get(ACCOUNTS + "/" + seller + "/balance?as_of=2020-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(0));
        assertBadRequestNaming(mockMvc.perform(get(ACCOUNTS + "/" + seller + "/balance?x=1")), "x");
    }
}
