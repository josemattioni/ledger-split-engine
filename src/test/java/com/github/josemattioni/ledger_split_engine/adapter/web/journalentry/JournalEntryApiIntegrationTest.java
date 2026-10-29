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
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class JournalEntryApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String twoLineBody(String code1, long amount1, String code2, long amount2) {
        return """
                {"lines":[{"account_code":"%s","amount_cents":%d},{"account_code":"%s","amount_cents":%d}]}
                """
                .formatted(code1, amount1, code2, amount2);
    }

    @Test
    void postsBalancedEntryThenBalanceReadsReflectIt() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        String body = twoLineBody(sellerPayable, 9750L, acquirerFee, -9750L);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry_id").exists())
                .andExpect(jsonPath("$.idempotency_key").value(idempotencyKey))
                // Present, with a null value: an ordinary entry reverses nothing, and the key is part of the shape.
                .andExpect(jsonPath("$", hasKey("reverses_entry_id")))
                .andExpect(jsonPath("$.reverses_entry_id").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].line_number").value(1))
                .andExpect(jsonPath("$.lines[0].account_code").value(sellerPayable))
                .andExpect(jsonPath("$.lines[1].line_number").value(2))
                .andExpect(jsonPath("$.lines[1].account_code").value(acquirerFee));

        mockMvc.perform(get("/api/v1/accounts/" + sellerPayable + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_code").value(sellerPayable))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.balance_cents").value(9750));

        mockMvc.perform(get("/api/v1/accounts/" + acquirerFee + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(-9750));
    }

    @Test
    void unbalancedEntryReturns422WithTheUnbalancedEntryType() throws Exception {
        // Two valid, distinct, non-zero lines that don't balance: the only defect is the imbalance.
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body = twoLineBody(sellerPayable, 100L, acquirerFee, -99L);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:unbalanced-entry"))
                .andExpect(jsonPath("$.detail", containsString("sum to 1")));

        mockMvc.perform(get("/api/v1/accounts/" + sellerPayable + "/balance"))
                .andExpect(jsonPath("$.balance_cents").value(0));
    }

    @Test
    void lineReferencingUnknownAccountReturns422ProblemJson() throws Exception {
        String known = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String unknown = uniqueCode("does_not_exist");
        String body = twoLineBody(known, 100L, unknown, -100L);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:unknown-account"))
                .andExpect(jsonPath("$.detail", containsString(unknown)));
    }

    @Test
    void aHugeMalformedAccountCodeOnALineIs422WithItsTypeAndAShortDetail() throws Exception {
        String known = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body = twoLineBody(known, 100L, "A".repeat(200_000), -100L);

        String response = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:invalid-account-code"))
                .andExpect(jsonPath("$.detail", containsString("\"" + "A".repeat(64) + "…\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).hasSizeLessThan(700).doesNotContain("A".repeat(65));
    }

    @Test
    void aHugeMalformedIdempotencyKeyGetsAShortDetail() throws Exception {
        String known = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String other = registerAccount("seller_payable", AccountType.LIABILITY);

        String response = mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "k".repeat(1_000))
                        .contentType("application/json")
                        .content(twoLineBody(known, 100L, other, -100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("\"" + "k".repeat(64) + "…\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).hasSizeLessThan(600).doesNotContain("k".repeat(65));
    }

    // ------------------------------------------------------------------ idempotent replay and key reuse

    private ResultActions postEntry(String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/journal-entries")
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(body));
    }

    private void assertBalance(String account, long expectedCents) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + account + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(expectedCents));
    }

    private void assertRejectedAsKeyReuse(ResultActions response, String idempotencyKey) throws Exception {
        response.andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:idempotency-key-reused"))
                // Exactly the key, and nothing about the stored entry's account codes or amounts.
                .andExpect(jsonPath("$.detail")
                        .value("Idempotency-Key \"" + idempotencyKey + "\" was already used for a different request"));
    }

    @Test
    void anIdenticalRetryReplaysTheOriginalResponseAndTheEntryIsCountedOnce() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        String body = twoLineBody(sellerPayable, 100L, acquirerFee, -100L);

        MvcResult first = postEntry(idempotencyKey, body)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();
        MvcResult retry = postEntry(idempotencyKey, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        // Byte-for-byte the same body: same entry_id, same created_at, same lines in the same order.
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertBalance(sellerPayable, 100L);
        assertBalance(acquirerFee, -100L);
    }

    @Test
    void theSameKeyWithADifferentAmountIsRejectedAsAReuseAndBalancesAreUnchanged() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated());

        // Still balanced: the amount is the only thing that differs.
        assertRejectedAsKeyReuse(
                postEntry(idempotencyKey, twoLineBody(sellerPayable, 150L, acquirerFee, -150L)), idempotencyKey);

        assertBalance(sellerPayable, 100L);
        assertBalance(acquirerFee, -100L);
    }

    @Test
    void theSameKeyWithTheLinesReorderedIsRejectedAsAReuseAndBalancesAreUnchanged() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated());

        assertRejectedAsKeyReuse(
                postEntry(idempotencyKey, twoLineBody(acquirerFee, -100L, sellerPayable, 100L)), idempotencyKey);

        assertBalance(sellerPayable, 100L);
        assertBalance(acquirerFee, -100L);
    }

    @Test
    void theSameKeyWithAnExtraLineIsRejectedAsAReuseAndBalancesAreUnchanged() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String commission = registerAccount("marketplace_commission", AccountType.REVENUE);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated());

        String threeLines =
                """
                {"lines":[{"account_code":"%s","amount_cents":100},{"account_code":"%s","amount_cents":-60},{"account_code":"%s","amount_cents":-40}]}
                """
                        .formatted(sellerPayable, commission, acquirerFee);
        assertRejectedAsKeyReuse(postEntry(idempotencyKey, threeLines), idempotencyKey);

        assertBalance(sellerPayable, 100L);
        assertBalance(commission, 0L);
        assertBalance(acquirerFee, -100L);
    }

    @Test
    void aRequestRejectedForALedgerRuleDoesNotConsumeTheKey() throws Exception {
        String known = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String unknown = uniqueCode("does_not_exist");
        String idempotencyKey = uniqueIdempotencyKey();

        postEntry(idempotencyKey, twoLineBody(known, 100L, unknown, -100L))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:unknown-account"));

        // The key is still unused, so this is a new post, not a replay.
        postEntry(idempotencyKey, twoLineBody(known, 100L, sellerPayable, -100L))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertBalance(known, 100L);
        assertBalance(sellerPayable, -100L);
    }

    @Test
    void aMalformedRequestDoesNotConsumeTheKey() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        String malformed =
                """
                {"lines":[{"account_code":"%s","amount_cents":"100"},{"account_code":"%s","amount_cents":-100}]}
                """
                        .formatted(sellerPayable, acquirerFee);

        postEntry(idempotencyKey, malformed).andExpect(status().isBadRequest());

        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertBalance(sellerPayable, 100L);
    }

    // Precedence test (two defects on purpose: a key already in use AND an unbalanced body). An
    // invalid request is rejected on its own merits, whatever its key.
    @Test
    void precedence_aReusedKeyWithAnUnbalancedBodyReportsTheUnbalancedEntry() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated());

        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -99L))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:unbalanced-entry"));

        assertBalance(sellerPayable, 100L);
    }

    // Precedence test (two defects on purpose: a key already in use with different lines AND an
    // unknown account). The key is checked before accounts are resolved, so the reuse is reported.
    @Test
    void precedence_aReusedKeyWithAnUnknownAccountReportsTheKeyReuse() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated());

        assertRejectedAsKeyReuse(
                postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, uniqueCode("does_not_exist"), -100L)),
                idempotencyKey);
    }

    @Test
    void missingIdempotencyKeyReturns400ProblemJson() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body = twoLineBody(sellerPayable, 100L, acquirerFee, -100L);

        mockMvc.perform(post("/api/v1/journal-entries").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void balanceOfAccountWithNoLinesReturnsZero() throws Exception {
        String account = registerAccount("acquirer_fee", AccountType.EXPENSE);

        mockMvc.perform(get("/api/v1/accounts/" + account + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(0));
    }

    @Test
    void balanceOfUnknownAccountReturns404ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + uniqueCode("unknown") + "/balance"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void balancedEntryOfLongMaxLinesIsRejectedAndLeavesBalancesReadable() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body = twoLineBody(sellerPayable, Long.MAX_VALUE, acquirerFee, -Long.MAX_VALUE);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:amount-limit-exceeded"))
                .andExpect(jsonPath("$.detail", containsString("1000000000000")));

        mockMvc.perform(get("/api/v1/accounts/" + sellerPayable + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(0));
    }

    @Test
    void postingAtTheLimitRepeatedlyKeepsTheBalanceReadable() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body = twoLineBody(sellerPayable, 1_000_000_000_000L, acquirerFee, -1_000_000_000_000L);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/journal-entries")
                            .header("Idempotency-Key", uniqueIdempotencyKey())
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/accounts/" + sellerPayable + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(3_000_000_000_000L));
    }

    @Test
    void stringAmountCentsIsRejectedAndNothingIsPosted() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body =
                """
                {"lines":[{"account_code":"%s","amount_cents":"-100"},{"account_code":"%s","amount_cents":100}]}
                """
                        .formatted(sellerPayable, acquirerFee);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/accounts/" + sellerPayable + "/balance"))
                .andExpect(jsonPath("$.balance_cents").value(0));
    }

    @Test
    void repeatedIdempotencyKeyHeaderIsRejectedAndNothingIsPosted() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body = twoLineBody(sellerPayable, 100L, acquirerFee, -100L);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", uniqueIdempotencyKey(), uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/accounts/" + sellerPayable + "/balance"))
                .andExpect(jsonPath("$.balance_cents").value(0));
    }

    // ------------------------------------------------------------------ degenerate entries (422, nothing posted)

    private void assertDegenerateRejectedAndNothingPosted(String body, String detailFragment, String... accounts)
            throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", uniqueIdempotencyKey())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:degenerate-entry"))
                .andExpect(jsonPath("$.detail", containsString(detailFragment)));

        for (String account : accounts) {
            mockMvc.perform(get("/api/v1/accounts/" + account + "/balance"))
                    .andExpect(jsonPath("$.balance_cents").value(0));
        }
    }

    @Test
    void emptyLinesReturns422NotA400() throws Exception {
        assertDegenerateRejectedAndNothingPosted("{\"lines\":[]}", "at least two lines (got 0)");
    }

    // Precedence test: a lone line is inherently two-defect (zero or unbalanced); the two-line
    // minimum wins, so it is reported as degenerate, not as unbalanced.
    @Test
    void precedence_aSingleLineIsReportedAsDegenerateNotAsUnbalanced() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String body =
                """
                {"lines":[{"account_code":"%s","amount_cents":100}]}
                """
                        .formatted(sellerPayable);

        assertDegenerateRejectedAndNothingPosted(body, "at least two lines (got 1)", sellerPayable);
    }

    @Test
    void aZeroAmountLineAmongOtherwiseValidLinesReturns422() throws Exception {
        String a = registerAccount("seller_payable", AccountType.LIABILITY);
        String b = registerAccount("marketplace_commission", AccountType.REVENUE);
        String c = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body =
                """
                {"lines":[{"account_code":"%s","amount_cents":250},{"account_code":"%s","amount_cents":0},{"account_code":"%s","amount_cents":-250}]}
                """
                        .formatted(a, b, c);

        assertDegenerateRejectedAndNothingPosted(body, "line 2 has a zero amount_cents", a, b, c);
    }

    @Test
    void aRepeatedAccountAcrossSeveralLinesReturns422() throws Exception {
        String a = registerAccount("seller_payable", AccountType.LIABILITY);
        String b = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String body =
                """
                {"lines":[{"account_code":"%s","amount_cents":100},{"account_code":"%s","amount_cents":50},{"account_code":"%s","amount_cents":-150}]}
                """
                        .formatted(a, a, b);

        assertDegenerateRejectedAndNothingPosted(
                body, "account_code " + a + " appears on more than one line (lines 1 and 2)", a, b);
    }

    @Test
    void aDebitAndCreditOnTheSameAccountReturns422InsteadOfPostingANetZeroEntry() throws Exception {
        String a = registerAccount("seller_payable", AccountType.LIABILITY);
        String body = twoLineBody(a, 700L, a, -700L);

        assertDegenerateRejectedAndNothingPosted(
                body, "account_code " + a + " appears on more than one line (lines 1 and 2)", a);
    }

    // ------------------------------------------------------------------ line limit, account-code format, repeated properties

    private List<String> registerAccounts(int count) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            codes.add(registerAccount("seller_payable", AccountType.LIABILITY));
        }
        return codes;
    }

    /** One non-zero line per account that sums to zero: +1 on all but the last, -(n - 1) on the last. */
    private static String bodyOverAccounts(List<String> codes) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < codes.size() - 1; i++) {
            lines.append("{\"account_code\":\"").append(codes.get(i)).append("\",\"amount_cents\":1},");
        }
        lines.append("{\"account_code\":\"")
                .append(codes.get(codes.size() - 1))
                .append("\",\"amount_cents\":")
                .append(-(codes.size() - 1))
                .append("}");
        return "{\"lines\":[" + lines + "]}";
    }

    @Test
    void anEntryOfExactlyTheMaximumNumberOfLinesIsAccepted() throws Exception {
        List<String> accounts = registerAccounts(100);

        postEntry(uniqueIdempotencyKey(), bodyOverAccounts(accounts))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines.length()").value(100))
                .andExpect(jsonPath("$.lines[0].line_number").value(1))
                .andExpect(jsonPath("$.lines[99].line_number").value(100));

        assertBalance(accounts.get(0), 1L);
        assertBalance(accounts.get(99), -99L);
    }

    @Test
    void anEntryOverTheMaximumNumberOfLinesReturns422AndNothingIsPosted() throws Exception {
        // 101 balanced non-zero lines on 101 registered, distinct accounts: the count is the only defect.
        List<String> accounts = registerAccounts(101);

        postEntry(uniqueIdempotencyKey(), bodyOverAccounts(accounts))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:line-limit-exceeded"))
                .andExpect(jsonPath("$.detail").value("journal entry has 101 lines; at most 100 are permitted"));

        for (String account : accounts) {
            assertBalance(account, 0L);
        }
    }

    // The registered account's code with one spelling change is the only defect: exact comparison, so
    // it is rejected as malformed rather than trimmed or case-folded onto the real account.
    private void assertMalformedSpellingRejectedAndNothingPosted(UnaryOperator<String> misspell) throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String malformed = misspell.apply(acquirerFee);

        postEntry(uniqueIdempotencyKey(), twoLineBody(sellerPayable, 100L, malformed, -100L))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:invalid-account-code"))
                .andExpect(jsonPath("$.detail", containsString("line 2: account_code \"" + malformed + "\" must be")));

        assertBalance(sellerPayable, 0L);
        assertBalance(acquirerFee, 0L);
    }

    @Test
    void anUpperCasedAccountCodeReturns422InvalidAccountCodeAndNothingIsPosted() throws Exception {
        assertMalformedSpellingRejectedAndNothingPosted(String::toUpperCase);
    }

    @Test
    void aLeadingSpaceOnARegisteredAccountCodeReturns422InvalidAccountCodeAndNothingIsPosted() throws Exception {
        assertMalformedSpellingRejectedAndNothingPosted(code -> " " + code);
    }

    @Test
    void aTrailingSpaceOnARegisteredAccountCodeReturns422InvalidAccountCodeAndNothingIsPosted() throws Exception {
        assertMalformedSpellingRejectedAndNothingPosted(code -> code + " ");
    }

    // With the last value kept, the first body would post -250/+250. It must be refused outright, and
    // because it is refused before anything is written, the same key is still free.
    @Test
    void aRepeatedAmountCentsIsRejectedWithNothingPostedAndTheKeyStaysUsable() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        String repeated =
                """
                {"lines":[{"account_code":"%s","amount_cents":0,"amount_cents":-250},{"account_code":"%s","amount_cents":250}]}
                """
                        .formatted(sellerPayable, acquirerFee);

        postEntry(idempotencyKey, repeated)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertBalance(sellerPayable, 0L);
        assertBalance(acquirerFee, 0L);

        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertBalance(sellerPayable, 100L);
    }

    // Precedence test (two defects on purpose: a key already in use AND a malformed account code). A
    // rule that needs no stored data is checked before the key, so the malformed code is what is reported.
    @Test
    void precedence_aReusedKeyWithAMalformedAccountCodeReportsTheMalformedCode() throws Exception {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee, -100L))
                .andExpect(status().isCreated());

        postEntry(idempotencyKey, twoLineBody(sellerPayable, 100L, acquirerFee.toUpperCase(), -100L))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:invalid-account-code"));

        assertBalance(sellerPayable, 100L);
        assertBalance(acquirerFee, -100L);
    }
}
