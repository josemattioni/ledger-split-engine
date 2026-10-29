package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.application.journalentry.AccountBalanceResult;
import com.github.josemattioni.ledger_split_engine.application.journalentry.ReadAccountBalance;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.AsOfNotSettledException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The balance endpoint with the use case mocked: the query string is read and judged here, so these tests
 * assert both what reaches the use case and that it is never reached for a bad request. The application decodes
 * the query string itself ({@code QueryString}), so {@code MockMvc} exercises the real decoding, malformed
 * percent-encodings included; the parameters go in the URL because that is where the application reads them
 * ({@code MockMvc}'s {@code .param()} sets no query string). Tomcat's own request-line handling is covered over a
 * socket in {@code AsOfQueryStringSocketTest}.
 */
@WebMvcTest(controllers = AccountBalanceController.class)
class AccountBalanceControllerTest {

    private static final String CODE = "seller_payable:1";
    private static final String PATH = "/api/v1/accounts/" + CODE + "/balance";
    private static final Instant THE_INSTANT = Instant.parse("2026-10-21T15:00:00Z");
    private static final String FIXED_DETAIL = "the query string is not valid percent-encoded UTF-8";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReadAccountBalance readAccountBalance;

    /** The balance request with this {@code as_of}, written as a client would: percent-encoded, so a {@code +} survives. */
    private static MockHttpServletRequestBuilder getAsOf(String decodedValue) {
        return get(URI.create(PATH + "?as_of=" + URLEncoder.encode(decodedValue, StandardCharsets.UTF_8)));
    }

    private void theAccountHasBalance(long cents) {
        given(readAccountBalance.execute(eq(CODE), any())).willAnswer(invocation ->
                new AccountBalanceResult(CODE, Currency.BRL, cents, invocation.getArgument(1)));
    }

    // ------------------------------------------------------------------ the response

    @Test
    void aCurrentBalanceHasTheAccountCodeCurrencyAndAmountAndANullAsOfThatIsStillPresent() throws Exception {
        theAccountHasBalance(9750);

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_code").value(CODE))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.balance_cents").value(9750))
                .andExpect(jsonPath("$", hasKey("as_of")))
                .andExpect(jsonPath("$.as_of").value(nullValue()));
        verify(readAccountBalance).execute(CODE, null);
    }

    @Test
    void anAccountWithNoLinesReturnsZero() throws Exception {
        theAccountHasBalance(0);

        mockMvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.balance_cents").value(0));
    }

    @Test
    void anAsOfBalanceEchoesTheInstantInUtcWhateverOffsetTheRequestUsed() throws Exception {
        theAccountHasBalance(4000);

        mockMvc.perform(getAsOf("2026-10-21T12:00:00-03:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.as_of").value("2026-10-21T15:00:00Z"))
                .andExpect(jsonPath("$.balance_cents").value(4000));
    }

    // ------------------------------------------------------------------ accepted spellings

    /** Each spelling, as it appears in the raw query string (so percent-encoded where it matters), and the instant it means. */
    @ParameterizedTest(name = "[{index}] as_of={0}")
    @CsvSource({
        "2026-10-21T15:00:00Z,                 2026-10-21T15:00:00Z",
        "2026-10-21T15:00:00%2B00:00,          2026-10-21T15:00:00Z",
        "2026-10-21T12:00:00%2D03:00,          2026-10-21T15:00:00Z",
        "2026-10-21T12:00:00-03:00,            2026-10-21T15:00:00Z",
        "2026-10-21T15:00:00.5Z,               2026-10-21T15:00:00.5Z",
        "2026-10-21T15:00:00.123Z,             2026-10-21T15:00:00.123Z",
        "2026-10-21T15:00:00.123456Z,          2026-10-21T15:00:00.123456Z",
        "2026-10-21T15:00:00.000000Z,          2026-10-21T15:00:00Z",
        "2026-10-21T15:00:00-00:00,            2026-10-21T15:00:00Z",
    })
    void everyAcceptedSpellingReachesTheUseCaseAsTheSameInstant(String spelling, String instant) throws Exception {
        theAccountHasBalance(1);

        mockMvc.perform(get(URI.create(PATH + "?as_of=" + spelling.trim()))).andExpect(status().isOk());

        verify(readAccountBalance).execute(CODE, Instant.parse(instant.trim()));
    }

    // ------------------------------------------------------------------ a malformed as_of is a 400 and reads nothing

    /** The values as the application sees them, after the query string is decoded. */
    @ParameterizedTest(name = "[{index}] as_of={0}")
    @ValueSource(strings = {
        "2026-10-21T15:00:00", // no offset
        "2026-10-21", // date only
        "2026-10-21T15:00Z", // no seconds
        "2026-10-21T15:00:00.1234567Z", // seven fractional digits
        "1792940400", // an epoch number
        "2026-10-21t15:00:00Z", // lowercase t
        "2026-10-21T15:00:00z", // lowercase z
        "2026-10-21T15:00:00 00:00", // an unencoded + decodes to a space
        "2026-13-21T15:00:00Z", // month 13
        "2026-10-21T25:00:00Z", // hour 25
        "2026-10-21T15:00:00+19:00", // beyond the JDK's offset range
        "2026-12-31T23:59:60Z", // a leap second
    })
    void aMalformedAsOfIsA400NamingAsOfAndTheUseCaseIsNeverCalled(String value) throws Exception {
        mockMvc.perform(getAsOf(value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("as_of")));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    @Test
    void anEmptyAsOfIsA400NotTheCurrentBalance() throws Exception {
        mockMvc.perform(getAsOf(""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("as_of must not be empty")));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    @Test
    void aRepeatedAsOfIsA400WhateverTheTwoValuesAre() throws Exception {
        mockMvc.perform(get(URI.create(PATH + "?as_of=2026-10-21T15:00:00Z&as_of=2026-10-21T15:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("at most once")));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    // ------------------------------------------------------------------ an unknown parameter is a 400 and reads nothing

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"asOf", "AS_OF", "asof", "x"})
    void anUnknownParameterIsA400NamingItNotTheCurrentBalance(String name) throws Exception {
        mockMvc.perform(get(URI.create(PATH + "?" + name + "=2026-10-21T15:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("\"" + name + "\"")));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    @Test
    void anExtraParameterNextToAValidAsOfIsA400NamingTheExtraOne() throws Exception {
        mockMvc.perform(get(URI.create(PATH + "?as_of=2026-10-21T15:00:00Z&x=1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("\"x\"")))
                .andExpect(jsonPath("$.detail", not(containsString("\"as_of\""))));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    @Test
    void twoUnknownParametersAreBothNamedInSortedOrder() throws Exception {
        mockMvc.perform(get(URI.create(PATH + "?x=1&AS_OF=2026-10-21T15:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("\"AS_OF\", \"x\"")));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    // ------------------------------------------------------------------ a malformed percent-encoding: one fixed detail

    // The application decodes the query string itself, so these are real malformed encodings, not a simulated failure.
    @ParameterizedTest
    @ValueSource(strings = {"as_of=%zz", "as_of=%", "as_of=%2", "as_of=%E9", "x=%zz", "%zz=1", "as_of=2026-10-21T15:00:00Z&x=%C0%AF"})
    void aBadPercentEncodingIsA400WithTheOneFixedDetailAndNothingOfWhatTheClientSent(String query) throws Exception {
        // URI.create refuses a malformed escape, so the raw query string is set on the request as the container hands it over.
        mockMvc.perform(get(PATH).with(request -> {
                    request.setQueryString(query);
                    return request;
                }))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(FIXED_DETAIL))
                .andExpect(content().string(not(containsString("%zz"))))
                .andExpect(content().string(not(containsString("%E9"))));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1", "=", "as_of=2026-10-21T15:00:00Z&=x"})
    void aParameterWithNoNameIsA400WithItsOwnFixedDetail(String query) throws Exception {
        mockMvc.perform(get(URI.create(PATH + "?" + query)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("the query string has a parameter with no name"));

        verify(readAccountBalance, never()).execute(any(), any());
    }

    // ------------------------------------------------------------------ the use case's own outcomes

    @Test
    void anUnsettledAsOfIsA422WithItsOwnProblemTypeStatingTheWindow() throws Exception {
        given(readAccountBalance.execute(eq(CODE), any())).willThrow(new AsOfNotSettledException());

        mockMvc.perform(getAsOf("2026-10-21T15:00:00Z"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ledger-split-engine:problem:as-of-not-settled"))
                .andExpect(jsonPath("$.detail", containsString("60 seconds")));
    }

    @Test
    void anUnknownAccountIsA404ProblemJsonNamingTheCode() throws Exception {
        given(readAccountBalance.execute(eq("unknown"), any())).willThrow(new AccountNotFoundException("unknown"));

        mockMvc.perform(get("/api/v1/accounts/unknown/balance"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("No account with code")))
                .andExpect(jsonPath("$.detail", containsString("unknown")));
    }
}
