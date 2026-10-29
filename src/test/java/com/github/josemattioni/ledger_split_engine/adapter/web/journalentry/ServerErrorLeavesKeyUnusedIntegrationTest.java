package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A request that fails with a 500 leaves its idempotency key unused: for a post (slice 4, "only a
 * successful post consumes a key") and for a reversal ("a rejected reversal does not consume its key").
 * The contract suite cannot reach this path, because a valid request never fails over HTTP; its two
 * {@code serverErrorLeavesKeyUnused} tests are {@code @Disabled} for that reason. So the fault is injected
 * here, on the real stack and a real database.
 *
 * <p>The fault is a failing {@code entry_lines} insert, the statement that follows the header insert in
 * {@code JdbcJournalEntryAdapter.insertOrFind}. The test does not take on trust that the key was written
 * first: the failing statement itself counts the header rows with the key from <em>inside</em> the
 * transaction (it sees its own uncommitted insert), and the test then counts them again from a separate
 * session once the request is over. One row inside and none outside is what "consumed in flight, rolled
 * back, unused" means. The fault fires once; the retry runs the real statement.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServerErrorLeavesKeyUnusedIntegrationTest extends AbstractIntegrationTest {

    private static final String ENTRIES = "/api/v1/journal-entries";
    private static final String HEADERS_WITH_KEY = "SELECT count(*) FROM journal_entries WHERE idempotency_key = ?";

    @Autowired
    private MockMvc mockMvc;

    /** The adapter's own JdbcTemplate, wrapped so one statement can be made to fail; every other call is real. */
    @MockitoSpyBean
    private JdbcTemplate spiedJdbc;

    private final AtomicInteger headerRowsSeenInsideTheTransaction = new AtomicInteger(-1);

    private String seller;
    private String fee;

    @BeforeEach
    void setUp() {
        seller = registerAccount("seller_payable", AccountType.LIABILITY);
        fee = registerAccount("acquirer_fee", AccountType.EXPENSE);
    }

    // ------------------------------------------------------------------ helpers

    /** The next {@code entry_lines} insert fails, after recording how many headers carry {@code key} in its own transaction. */
    private void failTheNextLinesInsert(String key) {
        clearInvocations(spiedJdbc); // so "ran twice" counts the failed request and its retry, not earlier setup
        doAnswer(invocation -> {
                    JdbcTemplate self = (JdbcTemplate) invocation.getMock();
                    headerRowsSeenInsideTheTransaction.set(self.queryForObject(HEADERS_WITH_KEY, Integer.class, key));
                    throw new DataAccessResourceFailureException("injected: the connection dropped while inserting the lines");
                })
                .doCallRealMethod()
                .when(spiedJdbc)
                .batchUpdate(startsWith("INSERT INTO entry_lines"), ArgumentMatchers.<List<Object[]>>any());
    }

    private void assertTheLinesInsertRanTwice() {
        verify(spiedJdbc, times(2)).batchUpdate(startsWith("INSERT INTO entry_lines"), ArgumentMatchers.<List<Object[]>>any());
    }

    /** Header rows with the key as another session sees them: committed ones only. */
    private int committedHeadersWithKey(String key) {
        return ownerJdbc().queryForObject(HEADERS_WITH_KEY, Integer.class, key);
    }

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

    private void assertBalances(long sellerCents) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + seller + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(sellerCents));
        mockMvc.perform(get("/api/v1/accounts/" + fee + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_cents").value(-sellerCents));
    }

    /** The generic 500: problem+json, no type, and none of the injected fault's wording. */
    private void assertGenericServerError(ResultActions response) throws Exception {
        response.andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    // ------------------------------------------------------------------ posts

    @Test
    void aPostThatFailsWhileInsertingItsLinesLeavesItsKeyUnused() throws Exception {
        String key = uniqueIdempotencyKey();
        failTheNextLinesInsert(key);

        assertGenericServerError(postEntry(key, twoLines(250)));

        assertThat(headerRowsSeenInsideTheTransaction).as("the key was written before the fault").hasValue(1);
        assertThat(committedHeadersWithKey(key)).as("and it was rolled back with the rest").isZero();
        assertBalances(0);

        // The retry is a first post, not a replay: 201 and no Idempotent-Replayed header.
        postEntry(key, twoLines(250))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.idempotency_key").value(key));
        assertBalances(250);
        assertTheLinesInsertRanTwice();
    }

    @Test
    void theKeyOfAFailedPostCanBeUsedForADifferentRequest() throws Exception {
        String key = uniqueIdempotencyKey();
        failTheNextLinesInsert(key);
        assertGenericServerError(postEntry(key, twoLines(250)));

        // Not a replay of the failed request and not "key reused for a different request": the key is unused.
        postEntry(key, twoLines(400))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.lines[0].amount_cents").value(400));
        assertBalances(400);
        assertTheLinesInsertRanTwice();
    }

    // ------------------------------------------------------------------ reversals

    @Test
    void aReversalThatFailsWhileInsertingItsLinesLeavesItsKeyUnusedAndTheEntryUnreversed() throws Exception {
        MvcResult original = postEntry(uniqueIdempotencyKey(), twoLines(9750))
                .andExpect(status().isCreated())
                .andReturn();
        String originalId = idOf(original);
        String reversalKey = uniqueIdempotencyKey();
        failTheNextLinesInsert(reversalKey);

        assertGenericServerError(reverse(originalId, reversalKey));

        assertThat(headerRowsSeenInsideTheTransaction).as("the key was written before the fault").hasValue(1);
        assertThat(committedHeadersWithKey(reversalKey)).as("and it was rolled back with the rest").isZero();
        assertThat(ownerJdbc().queryForObject(
                        "SELECT count(*) FROM journal_entries r JOIN journal_entries o ON o.id = r.reverses_entry_id"
                                + " WHERE o.entry_id = ?::uuid",
                        Integer.class,
                        originalId))
                .as("the original has no reversal")
                .isZero();
        assertBalances(9750);

        // The retry reverses it: 201, not a replay and not entry-already-reversed.
        reverse(originalId, reversalKey)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.idempotency_key").value(reversalKey))
                .andExpect(jsonPath("$.reverses_entry_id").value(originalId));
        assertBalances(0);
        assertTheLinesInsertRanTwice();
    }
}
