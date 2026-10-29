package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Reversal on the real stack (controller, use case, adapter, a real database as {@code ledger_app}).
 * Each negative test has one defect and asserts the problem {@code type}; a test that necessarily has
 * two is named {@code precedence_…} and asserts which rule wins.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntryReversalApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ENTRIES = "/api/v1/journal-entries";
    private static final String ALREADY_REVERSED = "urn:ledger-split-engine:problem:entry-already-reversed";
    private static final String CANNOT_REVERSE_A_REVERSAL = "urn:ledger-split-engine:problem:cannot-reverse-a-reversal";
    private static final String KEY_REUSED = "urn:ledger-split-engine:problem:idempotency-key-reused";

    @Autowired
    private MockMvc mockMvc;

    private String seller;
    private String fee;

    @BeforeEach
    void setUp() {
        seller = registerAccount("seller_payable", AccountType.LIABILITY);
        fee = registerAccount("acquirer_fee", AccountType.EXPENSE);
    }

    // ------------------------------------------------------------------ helpers

    private String twoLines(long sellerCents) {
        return """
                {"lines":[{"account_code":"%s","amount_cents":%d},{"account_code":"%s","amount_cents":%d}]}
                """
                .formatted(seller, sellerCents, fee, -sellerCents);
    }

    private ResultActions postEntry(String key, String body) throws Exception {
        return mockMvc.perform(post(ENTRIES).header("Idempotency-Key", key).contentType("application/json").content(body));
    }

    private ResultActions reverse(String entryId, String key) throws Exception {
        return mockMvc.perform(post(ENTRIES + "/{id}/reversal", entryId).header("Idempotency-Key", key));
    }

    private String idOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.entry_id");
    }

    /** Posts an ordinary entry that must succeed and returns its response. */
    private MvcResult post201(String key, String body) throws Exception {
        return postEntry(key, body).andExpect(status().isCreated()).andReturn();
    }

    private MvcResult reverse201(String entryId, String key) throws Exception {
        return reverse(entryId, key).andExpect(status().isCreated()).andReturn();
    }

    private void assertBalances(long sellerCents) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + seller + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(sellerCents));
        mockMvc.perform(get("/api/v1/accounts/" + fee + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(-sellerCents));
    }

    private void assertProblem(ResultActions response, int status, String type) throws Exception {
        response.andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        if (type == null) {
            response.andExpect(jsonPath("$.type").doesNotExist());
        } else {
            response.andExpect(jsonPath("$.type").value(type));
        }
    }

    // ------------------------------------------------------------------ the reversal itself

    @Test
    void reversingAPostedEntryNegatesItsLinesLinksToItAndRestoresEveryBalance() throws Exception {
        // A prior entry makes the balances before the original non-zero, so "restored" is not just "zero".
        post201(uniqueIdempotencyKey(), twoLines(30L));
        assertBalances(30L);
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        assertBalances(9780L);
        String reversalKey = uniqueIdempotencyKey();

        MvcResult reversal = reverse(idOf(original), reversalKey)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.idempotency_key").value(reversalKey))
                .andExpect(jsonPath("$.reverses_entry_id").value(idOf(original)))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].line_number").value(1))
                .andExpect(jsonPath("$.lines[0].account_code").value(seller))
                .andExpect(jsonPath("$.lines[0].amount_cents").value(-9750))
                .andExpect(jsonPath("$.lines[1].line_number").value(2))
                .andExpect(jsonPath("$.lines[1].account_code").value(fee))
                .andExpect(jsonPath("$.lines[1].amount_cents").value(9750))
                .andReturn();

        assertThat(idOf(reversal)).as("its own identity").isNotEqualTo(idOf(original));
        Instant originalAt = Instant.parse(JsonPath.read(original.getResponse().getContentAsString(), "$.created_at"));
        Instant reversalAt = Instant.parse(JsonPath.read(reversal.getResponse().getContentAsString(), "$.created_at"));
        assertThat(reversalAt).isAfterOrEqualTo(originalAt);
        assertBalances(30L);
    }

    @Test
    void everyLineOfAMultiLineEntryIsNegatedInOrder() throws Exception {
        String commission = registerAccount("marketplace_commission", AccountType.REVENUE);
        String body = """
                {"lines":[{"account_code":"%s","amount_cents":9750},{"account_code":"%s","amount_cents":-750},{"account_code":"%s","amount_cents":-9000}]}
                """
                .formatted(seller, commission, fee);
        MvcResult original = post201(uniqueIdempotencyKey(), body);

        reverse(idOf(original), uniqueIdempotencyKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].account_code").value(seller))
                .andExpect(jsonPath("$.lines[0].amount_cents").value(-9750))
                .andExpect(jsonPath("$.lines[1].account_code").value(commission))
                .andExpect(jsonPath("$.lines[1].amount_cents").value(750))
                .andExpect(jsonPath("$.lines[2].account_code").value(fee))
                .andExpect(jsonPath("$.lines[2].amount_cents").value(9000))
                .andExpect(jsonPath("$.lines[2].line_number").value(3));
    }

    @Test
    void anOrdinaryEntryStillCarriesANullLinkAfterItsReversal() throws Exception {
        String key = uniqueIdempotencyKey();
        MvcResult original = post201(key, twoLines(500L));
        reverse201(idOf(original), uniqueIdempotencyKey());

        // The original replayed after it has been reversed is exactly what it was: nothing was changed.
        MvcResult replay = postEntry(key, twoLines(500L))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$", hasKey("reverses_entry_id")))
                .andExpect(jsonPath("$.reverses_entry_id").value(nullValue()))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
    }

    @Test
    void anUpperCaseEntryIdInThePathIsAccepted() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(500L));

        reverse(idOf(original).toUpperCase(), uniqueIdempotencyKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reverses_entry_id").value(idOf(original)));
    }

    // ------------------------------------------------------------------ retries

    @Test
    void anIdenticalRetryReplaysTheReversalAndTheBalancesCountItOnce() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        String key = uniqueIdempotencyKey();
        MvcResult first = reverse201(idOf(original), key);

        // The entry is now reversed, yet the retry replays: it is answered from the key, never "already reversed".
        MvcResult retry = reverse(idOf(original), key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertBalances(0L);
    }

    // ------------------------------------------------------------------ the reversal rules

    @Test
    void aSecondReversalUnderANewKeyIsRejectedNamingTheOriginalAndLeavesTheBalancesAlone() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        reverse201(idOf(original), uniqueIdempotencyKey());

        ResultActions second = reverse(idOf(original), uniqueIdempotencyKey());

        assertProblem(second, 422, ALREADY_REVERSED);
        second.andExpect(jsonPath("$.detail", containsString(idOf(original))));
        assertBalances(0L);
    }

    @Test
    void reversingAReversalIsRejected() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        MvcResult reversal = reverse201(idOf(original), uniqueIdempotencyKey());

        ResultActions again = reverse(idOf(reversal), uniqueIdempotencyKey());

        assertProblem(again, 422, CANNOT_REVERSE_A_REVERSAL);
        again.andExpect(jsonPath("$.detail", containsString(idOf(reversal))))
                .andExpect(jsonPath("$.detail", containsString("post the original movement again as an ordinary entry")));
        assertBalances(0L);
    }

    @Test
    void aReversalIsUndoneByPostingTheOriginalMovementAgainAsAnOrdinaryEntry() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        reverse201(idOf(original), uniqueIdempotencyKey());
        assertBalances(0L);

        postEntry(uniqueIdempotencyKey(), twoLines(9750L))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.reverses_entry_id").value(nullValue()));

        assertBalances(9750L);
    }

    // ------------------------------------------------------------------ the request itself

    @Test
    void anUnknownEntryIdIsA404ProblemJsonWithTheDefaultType() throws Exception {
        UUID unknown = UUID.randomUUID();

        ResultActions response = reverse(unknown.toString(), uniqueIdempotencyKey());

        assertProblem(response, 404, null);
        response.andExpect(jsonPath("$.detail", containsString(unknown.toString())));
    }

    @Test
    void aNonCanonicalEntryIdIsA400AndNothingIsReversed() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));

        assertProblem(reverse("1-1-1-1-1", uniqueIdempotencyKey()), 400, null);

        // The original was not touched: it can still be reversed.
        reverse201(idOf(original), uniqueIdempotencyKey());
    }

    @Test
    void aMissingIdempotencyKeyIsA400AndNothingIsReversed() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));

        assertProblem(mockMvc.perform(post(ENTRIES + "/{id}/reversal", idOf(original))), 400, null);

        reverse201(idOf(original), uniqueIdempotencyKey());
    }

    @Test
    void aMalformedIdempotencyKeyIsA400AndNothingIsReversed() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));

        assertProblem(reverse(idOf(original), "not a valid key"), 400, null);

        reverse201(idOf(original), uniqueIdempotencyKey());
    }

    // A body would be ignored and answered with a full reversal: a client that sent lines wanted a partial one.
    @Test
    void aRequestBodyIsA400AndTheEntryIsNotReversed() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));

        assertProblem(
                mockMvc.perform(post(ENTRIES + "/{id}/reversal", idOf(original))
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(twoLines(100L))),
                400,
                null);

        assertBalances(9750L);
        reverse201(idOf(original), uniqueIdempotencyKey());
    }

    // ------------------------------------------------------------------ key reuse

    @Test
    void precedence_aKeyUsedByAnOrdinaryEntryIsReportedAsReuseForAValidTarget() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        String taken = uniqueIdempotencyKey();
        post201(taken, twoLines(100L));

        ResultActions response = reverse(idOf(original), taken);

        assertProblem(response, 422, KEY_REUSED);
        // Exactly the key, and nothing about the stored entry's account codes or amounts.
        response.andExpect(jsonPath("$.detail").value("Idempotency-Key \"" + taken + "\" was already used for a different request"));
        assertBalances(9850L);
    }

    @Test
    void precedence_aReusedKeyWithAnUnknownEntryIsA404BecauseTheTargetIsLoadedFirst() throws Exception {
        String taken = uniqueIdempotencyKey();
        post201(taken, twoLines(100L));

        assertProblem(reverse(UUID.randomUUID().toString(), taken), 404, null);
    }

    @Test
    void precedence_aMalformedKeyWithAnUnknownEntryIsA400BecauseTheRequestIsValidatedFirst() throws Exception {
        assertProblem(reverse(UUID.randomUUID().toString(), "not a valid key"), 400, null);
    }

    @Test
    void precedence_aKeyUsedByTheReversalOfAnotherEntryIsAReuse() throws Exception {
        MvcResult a = post201(uniqueIdempotencyKey(), twoLines(100L));
        MvcResult b = post201(uniqueIdempotencyKey(), twoLines(200L));
        String shared = uniqueIdempotencyKey();
        reverse201(idOf(a), shared);

        assertProblem(reverse(idOf(b), shared), 422, KEY_REUSED);

        // B was not reversed.
        reverse201(idOf(b), uniqueIdempotencyKey());
    }

    @Test
    void precedence_anOrdinaryPostUnderAReversalsKeyWithTheReversalsExactLinesIsAReuse() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        String key = uniqueIdempotencyKey();
        reverse201(idOf(original), key);

        // Identical lines to the reversal's, so only the link tells them apart.
        assertProblem(postEntry(key, twoLines(-9750L)), 422, KEY_REUSED);

        assertBalances(0L);
    }

    @Test
    void precedence_aUsedKeyIsReportedBeforeAnAlreadyReversedEntry() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        reverse201(idOf(original), uniqueIdempotencyKey());
        String taken = uniqueIdempotencyKey();
        post201(taken, twoLines(100L));

        assertProblem(reverse(idOf(original), taken), 422, KEY_REUSED);
    }

    @Test
    void precedence_aReversalOfAReversalIsReportedBeforeItsKeyIsConsidered() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        String reversalKey = uniqueIdempotencyKey();
        MvcResult reversal = reverse201(idOf(original), reversalKey);

        // Asking to reverse the reversal under the reversal's own key is not a replay of anything.
        assertProblem(reverse(idOf(reversal), reversalKey), 422, CANNOT_REVERSE_A_REVERSAL);
    }

    // ------------------------------------------------------------------ rejected requests do not consume their key

    @Test
    void aKeyFromARejectedSecondReversalIsStillUsableForAnOrdinaryEntry() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        reverse201(idOf(original), uniqueIdempotencyKey());
        String rejected = uniqueIdempotencyKey();
        assertProblem(reverse(idOf(original), rejected), 422, ALREADY_REVERSED);

        postEntry(rejected, twoLines(100L))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void aKeyFromAReversalOfAnUnknownEntryIsStillUsableForARealReversal() throws Exception {
        MvcResult original = post201(uniqueIdempotencyKey(), twoLines(9750L));
        String key = uniqueIdempotencyKey();
        assertProblem(reverse(UUID.randomUUID().toString(), key), 404, null);

        reverse(idOf(original), key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }
}
