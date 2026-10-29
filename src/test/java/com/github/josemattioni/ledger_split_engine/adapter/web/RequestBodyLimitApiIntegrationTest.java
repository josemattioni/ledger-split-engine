package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A request body larger than the limit (256 KiB) is a 413 problem+json on every endpoint that reads one, and nothing
 * is written, so the same request, key included, still works once it is small enough.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodyLimitApiIntegrationTest extends AbstractIntegrationTest {

    private static final int LIMIT = 262_144;
    private static final String DETAIL = "the request body is larger than 262144 bytes";

    @Autowired
    private MockMvc mockMvc;

    private String seller;
    private String fee;

    @BeforeEach
    void setUp() {
        seller = registerAccount("seller_payable", AccountType.LIABILITY);
        fee = registerAccount("acquirer_fee", AccountType.EXPENSE);
    }

    /** JSON closed with {@code }} after enough spaces to make the whole body exactly {@code totalBytes} long. */
    private static byte[] paddedTo(String jsonWithoutClosingBrace, int totalBytes) {
        int pad = totalBytes - jsonWithoutClosingBrace.getBytes(StandardCharsets.UTF_8).length - 1;
        return (jsonWithoutClosingBrace + " ".repeat(pad) + "}").getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions registerWithBody(byte[] body) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body));
    }

    private ResultActions postEntryWithBody(String key, byte[] body) throws Exception {
        return mockMvc.perform(post("/api/v1/journal-entries")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private void expectTooLarge(ResultActions response) throws Exception {
        response.andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(DETAIL))
                .andExpect(jsonPath("$.title").value("Content Too Large"));
    }

    // ------------------------------------------------------------------ the boundary

    @Test
    void aBodyOfExactlyTheLimitIsAccepted() throws Exception {
        String code = uniqueCode("seller_payable");

        registerWithBody(paddedTo("{\"code\":\"" + code + "\",\"type\":\"ASSET\"", LIMIT)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isOk());
    }

    @Test
    void aBodyOneByteOverTheLimitIs413AndRegistersNothing() throws Exception {
        String code = uniqueCode("seller_payable");

        expectTooLarge(registerWithBody(paddedTo("{\"code\":\"" + code + "\",\"type\":\"ASSET\"", LIMIT + 1)));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ the other endpoints that read a body

    @Test
    void anEntryOverTheLimitIs413PostsNothingAndLeavesItsKeyUnused() throws Exception {
        String key = uniqueIdempotencyKey();
        String small = "{\"lines\":[{\"account_code\":\"" + seller + "\",\"amount_cents\":500},{\"account_code\":\""
                + fee + "\",\"amount_cents\":-500}]}";

        expectTooLarge(postEntryWithBody(key, (small.substring(0, small.length() - 1) + " ".repeat(LIMIT) + "}").getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/v1/accounts/" + seller + "/balance")).andExpect(jsonPath("$.balance_cents").value(0));
        // Not a replay: the key was never consumed.
        postEntryWithBody(key, small.getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void aReversalWithAnOversizedBodyIs413NotThe400ForHavingABody() throws Exception {
        expectTooLarge(mockMvc.perform(post("/api/v1/journal-entries/" + UUID.randomUUID() + "/reversal")
                .header("Idempotency-Key", uniqueIdempotencyKey())
                .content(new byte[LIMIT + 1])));
    }

    @Test
    void aSmallBodyOnAReversalIsStillTheOrdinary400() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries/" + UUID.randomUUID() + "/reversal")
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("must not have a body")));
    }

    // ------------------------------------------------------------------ what it must not touch

    @Test
    void aGetWhoseBodyIsNeverReadIsNotAffectedByItsSize() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + seller + "/balance").content(new byte[LIMIT * 4]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(0));
    }
}
