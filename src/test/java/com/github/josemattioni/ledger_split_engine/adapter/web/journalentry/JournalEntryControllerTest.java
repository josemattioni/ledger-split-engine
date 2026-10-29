package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.application.journalentry.PostJournalEntry;
import com.github.josemattioni.ledger_split_engine.application.journalentry.PostJournalEntryResult;
import com.github.josemattioni.ledger_split_engine.application.journalentry.ReverseJournalEntry;
import com.github.josemattioni.ledger_split_engine.config.JacksonConfig;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.CannotReverseAReversalException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKeyReusedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.MixedCurrencyException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.UnknownAccountException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest(controllers = JournalEntryController.class)
@Import(JacksonConfig.class)
class JournalEntryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostJournalEntry postJournalEntry;

    @MockitoBean
    private ReverseJournalEntry reverseJournalEntry;

    private static JournalEntry storedEntry() {
        return new JournalEntry(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "key-1",
                List.of(new EntryLine(1, "seller_payable:1", 9750L), new EntryLine(2, "acquirer_fee", -9750L)),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static final String STORED_ENTRY_BODY =
            """
            {"lines":[{"account_code":"seller_payable:1","amount_cents":9750},{"account_code":"acquirer_fee","amount_cents":-9750}]}
            """;

    @Test
    void aCreatedResultReturns201WithEntryIdIdempotencyKeyCreatedAtAndOrderedLinesAndNoReplayHeader()
            throws Exception {
        given(postJournalEntry.execute(any())).willReturn(new PostJournalEntryResult.Created(storedEntry()));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(STORED_ENTRY_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.entry_id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.idempotency_key").value("key-1"))
                .andExpect(jsonPath("$.created_at").exists())
                // Present, with a null value: an ordinary entry reverses nothing, and the key is part of the shape.
                .andExpect(jsonPath("$", hasKey("reverses_entry_id")))
                .andExpect(jsonPath("$.reverses_entry_id").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].line_number").value(1))
                .andExpect(jsonPath("$.lines[0].account_code").value("seller_payable:1"))
                .andExpect(jsonPath("$.lines[0].amount_cents").value(9750))
                .andExpect(jsonPath("$.lines[1].line_number").value(2))
                .andExpect(jsonPath("$.lines[1].account_code").value("acquirer_fee"))
                .andExpect(jsonPath("$.lines[1].amount_cents").value(-9750));
    }

    @Test
    void aReplayedResultReturns201WithTheStoredBodyAndTheReplayHeader() throws Exception {
        given(postJournalEntry.execute(any())).willReturn(new PostJournalEntryResult.Replayed(storedEntry()));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(STORED_ENTRY_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.entry_id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.idempotency_key").value("key-1"))
                .andExpect(jsonPath("$.created_at").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.lines[0].line_number").value(1))
                .andExpect(jsonPath("$.lines[0].account_code").value("seller_payable:1"))
                .andExpect(jsonPath("$.lines[0].amount_cents").value(9750))
                .andExpect(jsonPath("$.lines[1].line_number").value(2))
                .andExpect(jsonPath("$.lines[1].account_code").value("acquirer_fee"))
                .andExpect(jsonPath("$.lines[1].amount_cents").value(-9750));
    }

    @Test
    void aReusedKeyReturns422WithTheIdempotencyKeyReusedTypeNamingTheKey() throws Exception {
        given(postJournalEntry.execute(any())).willThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:idempotency-key-reused"))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key \"key-1\" was already used for a different request"));
    }

    @Test
    void missingIdempotencyKeyHeaderReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries").contentType("application/json").content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void lineMissingAmountCentsReturns400ProblemJson() throws Exception {
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":100},{"account_code":"seller_payable:1"}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void nonIntegralAmountCentsReturns400ProblemJson() throws Exception {
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":9750.9},{"account_code":"seller_payable:1","amount_cents":-100}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void nullAccountCodeReturns400ProblemJson() throws Exception {
        String body = """
                {"lines":[{"account_code":null,"amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-100}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void nullLinesReturns400ProblemJson() throws Exception {
        String body = """
                {"lines":null}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void nullElementInLinesReturns400ProblemJson() throws Exception {
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":100},null]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /** A balanced request body, so it clears {@code JournalEntry}'s own domain validation and reaches the mocked use case. */
    private static String validBody() {
        return """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-100}]}
                """;
    }

    @Test
    void unbalancedJournalEntryReturns422WithTheUnbalancedEntryType() throws Exception {
        // Real body, no use-case stub: two valid lines of +100 / -99 are the only defect.
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-99}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:unbalanced-entry"))
                .andExpect(jsonPath("$.detail").value("journal entry lines sum to 1, not 0"));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void unknownAccountReturns422ProblemJson() throws Exception {
        given(postJournalEntry.execute(any())).willThrow(new UnknownAccountException(Set.of("acquirer_fee")));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:unknown-account"))
                .andExpect(jsonPath("$.detail").value(containsString("acquirer_fee")));
    }

    @Test
    void mixedCurrencyReturns422ProblemJson() throws Exception {
        given(postJournalEntry.execute(any())).willThrow(new MixedCurrencyException(Set.of("BRL", "USD")));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:mixed-currency"))
                .andExpect(jsonPath("$.detail").value(containsString("BRL")));
    }

    // ------------------------------------------------------------------ the reversal outcomes, by exception type
    // The use case is mocked and made to throw each reversal exception: what is pinned here is the mapping
    // (status, problem type, detail), not who throws it.

    private static final UUID SOME_ENTRY = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void reversingAReversalReturns422WithTheCannotReverseAReversalType() throws Exception {
        given(postJournalEntry.execute(any())).willThrow(new CannotReverseAReversalException(SOME_ENTRY));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:cannot-reverse-a-reversal"))
                .andExpect(jsonPath("$.detail", containsString(SOME_ENTRY.toString())))
                .andExpect(jsonPath("$.detail", containsString("post the original movement again")));
    }

    @Test
    void reversingAnEntryTwiceReturns422WithTheEntryAlreadyReversedType() throws Exception {
        given(postJournalEntry.execute(any())).willThrow(new EntryAlreadyReversedException(SOME_ENTRY));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:entry-already-reversed"))
                .andExpect(jsonPath("$.detail").value("entry " + SOME_ENTRY + " has already been reversed"));
    }

    // A 404 keeps the default problem type, like every other non-422 error: it has no `type` of its own.
    @Test
    void aMissingEntryReturns404WithTheDefaultTypeAndNamesTheEntryId() throws Exception {
        given(postJournalEntry.execute(any())).willThrow(new JournalEntryNotFoundException(SOME_ENTRY));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.detail").value("No journal entry with entry_id \"" + SOME_ENTRY + "\""));
    }

    @Test
    void amountLimitExceededReturns422WithTheAmountLimitExceededType() throws Exception {
        // Real body, no use-case stub: a balanced pair of +/-2e12 is the only defect.
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":2000000000000},{"account_code":"seller_payable:1","amount_cents":-2000000000000}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:amount-limit-exceeded"))
                .andExpect(jsonPath("$.detail").value(containsString("1000000000000")));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    // ------------------------------------------------------------------ degenerate entries: 422, real bodies

    private void assertDegenerate422(String body, String detailFragment) throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:degenerate-entry"))
                .andExpect(jsonPath("$.detail").value(containsString(detailFragment)));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void emptyLinesReturns422NotA400BecauseTheGuardLivesInTheDomain() throws Exception {
        assertDegenerate422("{\"lines\":[]}", "at least two lines (got 0)");
    }

    // Precedence test: a lone line is inherently two-defect (zero or unbalanced); the two-line
    // minimum wins, so it is reported as degenerate, not as unbalanced.
    @Test
    void precedence_aSingleLineReturnsTheDegenerateTypeNotTheUnbalancedOne() throws Exception {
        assertDegenerate422(
                """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":100}]}
                """,
                "at least two lines (got 1)");
    }

    @Test
    void aZeroAmountLineAmongOtherwiseValidLinesReturns422() throws Exception {
        assertDegenerate422(
                """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":250},{"account_code":"seller_payable:1","amount_cents":0},{"account_code":"marketplace_commission","amount_cents":-250}]}
                """,
                "line 2 has a zero amount_cents");
    }

    @Test
    void aRepeatedAccountOnABalancedEntryReturns422() throws Exception {
        assertDegenerate422(
                """
                {"lines":[{"account_code":"seller_payable:1","amount_cents":100},{"account_code":"seller_payable:1","amount_cents":50},{"account_code":"acquirer_fee","amount_cents":-150}]}
                """,
                "account_code seller_payable:1 appears on more than one line (lines 1 and 2)");
    }

    // ------------------------------------------------------------------ line limit and account-code format: 422, real bodies

    /** {@code count} non-zero lines on distinct, well-formed accounts that sum to zero. */
    private static String bodyWithLines(int count) {
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i < count; i++) {
            lines.append("{\"account_code\":\"acct_").append(i).append("\",\"amount_cents\":1},");
        }
        lines.append("{\"account_code\":\"acct_").append(count).append("\",\"amount_cents\":").append(-(count - 1)).append("}");
        return "{\"lines\":[" + lines + "]}";
    }

    @Test
    void moreThanTheMaximumNumberOfLinesReturns422WithTheLineLimitExceededType() throws Exception {
        // Real body, no use-case stub: 101 balanced non-zero lines on distinct accounts are the only defect.
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(bodyWithLines(101)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:line-limit-exceeded"))
                .andExpect(jsonPath("$.detail").value("journal entry has 101 lines; at most 100 are permitted"));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void anUpperCasedAccountCodeReturns422WithTheInvalidAccountCodeTypeNamingTheLine() throws Exception {
        // Real body, no use-case stub: the upper-cased code on line 2 is the only defect.
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":100},{"account_code":"SELLER_PAYABLE:1","amount_cents":-100}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:invalid-account-code"))
                .andExpect(jsonPath("$.detail", containsString("line 2: account_code \"SELLER_PAYABLE:1\" must be lowercase snake_case")));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    // ------------------------------------------------------------------ backstop failures are 500, never 409

    private void assertGeneric500(Throwable failure) throws Exception {
        given(postJournalEntry.execute(any())).willThrow(failure);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    void aUniqueViolationThatIsNotTheIdempotencyKeyReturns500NotA409() throws Exception {
        // e.g. entry_lines_entry_account_uq: unreachable through the API; if it fires it is a bug.
        assertGeneric500(new DuplicateKeyException("duplicate key value violates unique constraint"));
    }

    @Test
    void aCheckViolationFromTheStorageLayerReturns500() throws Exception {
        assertGeneric500(new DataIntegrityViolationException("violates check constraint"));
    }

    // A component inside the system rejecting an argument is a defect in the system, not a mistake by
    // the client: only a failure while building domain objects from the request is a 400.
    @Test
    void anIllegalArgumentExceptionFromTheUseCaseIs500NotA400() throws Exception {
        assertGeneric500(new IllegalArgumentException("stored row violates an internal invariant"));
    }

    @Test
    void aCommitTimeBackstopFailureReturns500() throws Exception {
        // e.g. LDG03 (fewer than two lines) surfacing when the transaction commits.
        assertGeneric500(new TransactionSystemException("Could not commit JDBC transaction"));
    }

    // ------------------------------------------------------------------ strict JSON typing

    // The repeat is the only defect: with the last value kept the entry would balance (-250 / +250)
    // and post. A repeated property must be refused, not resolved to one of its values.
    @Test
    void aLineRepeatingAmountCentsReturns400AndNeverReachesTheUseCase() throws Exception {
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":0,"amount_cents":-250},{"account_code":"seller_payable:1","amount_cents":250}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").doesNotExist());
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void aRepeatedLinesPropertyReturns400AndNeverReachesTheUseCase() throws Exception {
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-100}],"lines":[{"account_code":"acquirer_fee","amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-100}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").doesNotExist());
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void stringAmountCentsReturns400AndNeverReachesTheUseCase() throws Exception {
        String body = """
                {"lines":[{"account_code":"acquirer_fee","amount_cents":"-100"},{"account_code":"seller_payable:1","amount_cents":100}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void numericAccountCodeReturns400AndNeverReachesTheUseCase() throws Exception {
        String body = """
                {"lines":[{"account_code":12345,"amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-100}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void booleanAccountCodeReturns400AndNeverReachesTheUseCase() throws Exception {
        String body = """
                {"lines":[{"account_code":true,"amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-100}]}
                """;

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    // ------------------------------------------------------------------ exactly one Idempotency-Key header

    @Test
    void twoIdempotencyKeyHeadersReturn400AndNeverReachTheUseCase() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "k1", "k2")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void singleHeaderValueContainingACommaIsOneKey() throws Exception {
        given(postJournalEntry.execute(any()))
                .willReturn(new PostJournalEntryResult.Created(new JournalEntry(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "k1,k2",
                        List.of(new EntryLine(1, "acquirer_fee", 100L), new EntryLine(2, "seller_payable:1", -100L)),
                        Instant.parse("2026-01-01T00:00:00Z"))));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "k1,k2")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isCreated());

        ArgumentCaptor<JournalEntry> posted = ArgumentCaptor.forClass(JournalEntry.class);
        then(postJournalEntry).should().execute(posted.capture());
        assertThat(posted.getValue().idempotencyKey()).isEqualTo("k1,k2");
    }

    // ------------------------------------------------------------------ POST /{entry_id}/reversal

    private static final UUID TARGET = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static MockHttpServletRequestBuilder reversalOf(String entryIdInPath) {
        return post("/api/v1/journal-entries/{id}/reversal", entryIdInPath).header("Idempotency-Key", "reversal-key");
    }

    /** The reversal of TARGET's two-line entry, as the use case would return it. */
    private static JournalEntry storedReversal() {
        return new JournalEntry(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                "reversal-key",
                List.of(new EntryLine(1, "seller_payable:1", -9750L), new EntryLine(2, "acquirer_fee", 9750L)),
                Instant.parse("2026-01-02T00:00:00Z"),
                TARGET);
    }

    @Test
    void aCreatedReversalReturns201WithTheLinkAndNoReplayHeader() throws Exception {
        given(reverseJournalEntry.execute(eq(TARGET), eq("reversal-key")))
                .willReturn(new PostJournalEntryResult.Created(storedReversal()));

        mockMvc.perform(reversalOf(TARGET.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.entry_id").value("55555555-5555-5555-5555-555555555555"))
                .andExpect(jsonPath("$.idempotency_key").value("reversal-key"))
                .andExpect(jsonPath("$.reverses_entry_id").value(TARGET.toString()))
                .andExpect(jsonPath("$.lines[0].line_number").value(1))
                .andExpect(jsonPath("$.lines[0].account_code").value("seller_payable:1"))
                .andExpect(jsonPath("$.lines[0].amount_cents").value(-9750))
                .andExpect(jsonPath("$.lines[1].amount_cents").value(9750));
    }

    @Test
    void aReplayedReversalReturnsTheStoredBodyAndTheReplayHeader() throws Exception {
        given(reverseJournalEntry.execute(eq(TARGET), eq("reversal-key")))
                .willReturn(new PostJournalEntryResult.Replayed(storedReversal()));

        mockMvc.perform(reversalOf(TARGET.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.entry_id").value("55555555-5555-5555-5555-555555555555"))
                .andExpect(jsonPath("$.created_at").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.reverses_entry_id").value(TARGET.toString()));
    }

    // Java's UUID.fromString would take "1-1-1-1-1" as 00000001-0001-0001-0001-000000000001; the path value
    // must be the canonical 8-4-4-4-12 form or it is a malformed request, never reshaped into a UUID.
    @ParameterizedTest(name = "[{index}] ''{0}''")
    @ValueSource(strings = {
        "not-a-uuid",
        "1-1-1-1-1",
        "44444444444444444444444444444444",
        " 44444444-4444-4444-4444-444444444444",
        "44444444-4444-4444-4444-444444444444 ",
        "{44444444-4444-4444-4444-444444444444}",
        "44444444-4444-4444-4444-44444444444",
        "44444444-4444-4444-4444-4444444444444",
        "g4444444-4444-4444-4444-444444444444"
    })
    void aMalformedEntryIdReturns400AndNeverReachesTheUseCase(String entryIdInPath) throws Exception {
        mockMvc.perform(reversalOf(entryIdInPath))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.detail", containsString("canonical form")));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void anUpperCaseEntryIdIsAcceptedAndReachesTheUseCaseAsTheSameUuid() throws Exception {
        given(reverseJournalEntry.execute(eq(TARGET), eq("reversal-key")))
                .willReturn(new PostJournalEntryResult.Created(storedReversal()));

        mockMvc.perform(reversalOf(TARGET.toString().toUpperCase()))
                .andExpect(status().isCreated());

        then(reverseJournalEntry).should().execute(TARGET, "reversal-key");
    }

    @Test
    void aMissingIdempotencyKeyReturns400AndNeverReachesTheUseCase() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversal", TARGET.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void twoIdempotencyKeyHeadersOnAReversalReturn400AndNeverReachTheUseCase() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversal", TARGET.toString())
                        .header("Idempotency-Key", "k1", "k2"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    // The key's shape is checked here, in the controller, before the use case: inside it, the same
    // IllegalArgumentException would be an internal error (500), not the client's 400.
    @ParameterizedTest(name = "[{index}] ''{0}''")
    @ValueSource(strings = {"bad key", "tab\there", "caf\u00e9"})
    void aMalformedIdempotencyKeyOnAReversalReturns400AndNeverReachesTheUseCase(String key) throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversal", TARGET.toString()).header("Idempotency-Key", key))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("idempotency_key must be 1-255 visible ASCII characters")));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void anOverLongIdempotencyKeyOnAReversalReturns400AndNeverReachesTheUseCase() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversal", TARGET.toString())
                        .header("Idempotency-Key", "k".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    // A body would be ignored by the server and answered with a FULL reversal, which is exactly what a
    // client that tried to send lines (a partial reversal) must not get.
    @Test
    void aBodyWithLinesIsRejectedSoAPartialReversalIsNeverAnsweredWithAFullOne() throws Exception {
        mockMvc.perform(reversalOf(TARGET.toString()).contentType("application/json").content(STORED_ENTRY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("must not have a body")));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void anEmptyJsonObjectIsStillABodyAndIsRejected() throws Exception {
        mockMvc.perform(reversalOf(TARGET.toString()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void aPlainTextBodyIsRejectedToo() throws Exception {
        mockMvc.perform(reversalOf(TARGET.toString()).contentType("text/plain").content("reverse it"))
                .andExpect(status().isBadRequest());
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void anEmptyBodyWithAJsonContentTypeIsAccepted() throws Exception {
        given(reverseJournalEntry.execute(eq(TARGET), eq("reversal-key")))
                .willReturn(new PostJournalEntryResult.Created(storedReversal()));

        mockMvc.perform(reversalOf(TARGET.toString()).contentType("application/json").content(""))
                .andExpect(status().isCreated());
    }

    // ---- every outcome the use case can raise reaches the client through the new endpoint

    @Test
    void anUnknownEntryOnTheReversalEndpointReturns404() throws Exception {
        given(reverseJournalEntry.execute(any(), any())).willThrow(new JournalEntryNotFoundException(TARGET));

        mockMvc.perform(reversalOf(TARGET.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString(TARGET.toString())));
    }

    @Test
    void aReversalOfAReversalOnTheReversalEndpointReturns422() throws Exception {
        given(reverseJournalEntry.execute(any(), any())).willThrow(new CannotReverseAReversalException(TARGET));

        mockMvc.perform(reversalOf(TARGET.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:cannot-reverse-a-reversal"));
    }

    @Test
    void aSecondReversalOnTheReversalEndpointReturns422() throws Exception {
        given(reverseJournalEntry.execute(any(), any())).willThrow(new EntryAlreadyReversedException(TARGET));

        mockMvc.perform(reversalOf(TARGET.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:entry-already-reversed"))
                .andExpect(jsonPath("$.detail", containsString(TARGET.toString())));
    }

    @Test
    void aReusedKeyOnTheReversalEndpointReturns422NamingTheKeyOnly() throws Exception {
        given(reverseJournalEntry.execute(any(), any())).willThrow(new IdempotencyKeyReusedException("reversal-key"));

        mockMvc.perform(reversalOf(TARGET.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:idempotency-key-reused"))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key \"reversal-key\" was already used for a different request"));
    }

    // ---- a required field that is missing, null or blank is named, with the request's own field names and index

    private void postAndExpectDetail(String body, String detail) throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(detail));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"lines\":null}"})
    void missingOrNullLinesIsNamed(String body) throws Exception {
        postAndExpectDetail(body, "lines must not be null");
    }

    @Test
    void aLineWithoutAnAmountNamesTheLineAndTheFieldInSnakeCase() throws Exception {
        postAndExpectDetail(
                "{\"lines\":[{\"account_code\":\"a\"},{\"account_code\":\"b\",\"amount_cents\":-1}]}",
                "lines[0].amount_cents must not be null");
    }

    @Test
    void aBlankAccountCodeAndAMissingAmountOnDifferentLinesAreBothNamedInSortedOrder() throws Exception {
        postAndExpectDetail(
                "{\"lines\":[{\"account_code\":\"a\",\"amount_cents\":null},{\"account_code\":\" \",\"amount_cents\":-1}]}",
                "lines[0].amount_cents must not be null; lines[1].account_code must not be blank");
    }

    @Test
    void aNullLineIsNamedByItsIndex() throws Exception {
        postAndExpectDetail("{\"lines\":[null,{\"account_code\":\"b\",\"amount_cents\":-1}]}", "lines[0] must not be null");
    }

    // 25 broken lines must not answer with 25 entries: the first ten, then a count.
    @Test
    void manyViolationsAreCutAtTenAndTheRestAreCounted() throws Exception {
        String line = "{\"account_code\":\"a\"}";
        String body = "{\"lines\":[" + String.join(",", java.util.Collections.nCopies(25, line)) + "]}";
        // The details are sorted as text (lines[10] before lines[2]), so the expected ten are the first ten of that order.
        String firstTen = IntStream.range(0, 25)
                .mapToObj(i -> "lines[" + i + "].amount_cents must not be null")
                .sorted()
                .limit(10)
                .collect(Collectors.joining("; "));

        postAndExpectDetail(body, firstTen + "; and 15 more");
    }

    // An unrecognized field inside a line is named too, wherever it is, without its position or its value.
    @Test
    void anUnrecognizedFieldInsideALineIsNamedInTheDetail() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("{\"lines\":[{\"account_code\":\"a\",\"amount_cents\":1,\"memo\":\"secret\"},"
                                + "{\"account_code\":\"b\",\"amount_cents\":-1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("unrecognized field \"memo\""));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    // ---- a malformed entry_id is named by its first 64 characters only, however long the path

    @Test
    void aHugeMalformedEntryIdIsNamedByItsFirstSixtyFourCharactersOnly() throws Exception {
        String huge = "z".repeat(5_000);

        mockMvc.perform(reversalOf(huge))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("entry_id must be a UUID in its canonical form (8-4-4-4-12 hexadecimal digits): \""
                        + "z".repeat(64) + "…\""));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    @Test
    void aShortMalformedEntryIdIsStillNamedWhole() throws Exception {
        mockMvc.perform(reversalOf("not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("entry_id must be a UUID in its canonical form (8-4-4-4-12 hexadecimal digits): \"not-a-uuid\""));
    }

    // ---- no entry endpoint reads a query parameter, so any is refused rather than silently dropped (the string is
    // in the URL, not in .param(): MockMvc's .param() sets no query string, which is what the rule looks at)

    @ParameterizedTest
    @ValueSource(strings = {"x=1", "idempotency_key=other-key", "as_of=2026-10-21T15:00:00Z"})
    void postingWithAQueryParameterReturns400NamingItAndNeverReachesTheUseCase(String query) throws Exception {
        String name = query.substring(0, query.indexOf('='));

        mockMvc.perform(post("/api/v1/journal-entries?" + query)
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(STORED_ENTRY_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("\"" + name + "\"")))
                .andExpect(jsonPath("$.detail", containsString("accepts no query parameters")));
        then(postJournalEntry).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x=1", "idempotency_key=other-key", "reverses_entry_id=" + "22222222-2222-2222-2222-222222222222"})
    void reversingWithAQueryParameterReturns400NamingItAndNeverReachesTheUseCase(String query) throws Exception {
        String name = query.substring(0, query.indexOf('='));

        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversal?" + query, TARGET.toString())
                        .header("Idempotency-Key", "reversal-key"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("\"" + name + "\"")));
        then(reverseJournalEntry).shouldHaveNoInteractions();
    }

    // Outside request-to-domain construction an IllegalArgumentException is the system's own defect.
    @Test
    void anIllegalArgumentExceptionFromTheReversalUseCaseIs500NotA400() throws Exception {
        given(reverseJournalEntry.execute(any(), any()))
                .willThrow(new IllegalArgumentException("only a stored entry can be reversed: it has no entry_id"));

        mockMvc.perform(reversalOf(TARGET.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }
}
