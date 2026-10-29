package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.josemattioni.ledger_split_engine.application.journalentry.PostJournalEntry;
import com.github.josemattioni.ledger_split_engine.application.journalentry.ReverseJournalEntry;
import com.github.josemattioni.ledger_split_engine.config.JacksonConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code journal-entries}: "Storage-layer backstop failures are internal errors". The scenarios say the failure is
 * answered with a generic {@code 500} <em>and logged for investigation</em>. The {@code 500} is covered next door
 * ({@code JournalEntryControllerTest}); the log half is not observable over HTTP, which is why the black-box contract
 * suite can only skip it, so it is checked here, where the log can be read: an error-level event that carries the
 * failure, while the response carries none of it.
 */
@WebMvcTest(controllers = JournalEntryController.class)
@Import(JacksonConfig.class)
class BackstopFailureIsLoggedTest {

    private static final String HANDLER_LOGGER = "com.github.josemattioni.ledger_split_engine.adapter.web.ApiExceptionHandler";
    private static final String ENTRY_ID = "44444444-4444-4444-4444-444444444444";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostJournalEntry postJournalEntry;

    @MockitoBean
    private ReverseJournalEntry reverseJournalEntry;

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(HANDLER_LOGGER);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureTheHandlersLog() {
        logged.start();
        handlerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseTheHandlersLog() {
        handlerLogger.detachAppender(logged);
        logged.stop();
    }

    private List<ILoggingEvent> errorEvents() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private void assertLoggedAtErrorLevelWith(String failureMessage) {
        assertThat(errorEvents())
                .as("an error-level log entry for the backstop failure")
                .anySatisfy(event -> {
                    assertThat(event.getThrowableProxy()).as("the failure travels with the log entry").isNotNull();
                    assertThat(event.getThrowableProxy().getMessage()).contains(failureMessage);
                });
    }

    @Test
    void anEntryShapeBackstopFailureIsAnswered500AndLoggedAtErrorLevel() throws Exception {
        String failure = "journal entry 7 has 1 line(s); at least 2 are required";
        given(postJournalEntry.execute(any())).willThrow(new DataIntegrityViolationException(failure));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content(
                                """
                                {"lines":[{"account_code":"acquirer_fee","amount_cents":100},{"account_code":"seller_payable:1","amount_cents":-100}]}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("at least 2"))));

        assertLoggedAtErrorLevelWith(failure);
    }

    @Test
    void aReversalBackstopFailureIsAnswered500AndLoggedAtErrorLevel() throws Exception {
        String failure = "journal entry 9 is not the exact negation of entry 7";
        given(reverseJournalEntry.execute(any(), any())).willThrow(new DataIntegrityViolationException(failure));

        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversal", ENTRY_ID).header("Idempotency-Key", "reversal-key"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("negation"))));

        assertLoggedAtErrorLevelWith(failure);
    }
}
