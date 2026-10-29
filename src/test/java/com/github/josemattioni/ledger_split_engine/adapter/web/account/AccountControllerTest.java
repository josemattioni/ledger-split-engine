package com.github.josemattioni.ledger_split_engine.adapter.web.account;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.application.account.RegisterAccount;
import com.github.josemattioni.ledger_split_engine.config.JacksonConfig;
import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountAlreadyExistsException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AccountController.class)
// The real use case over the mocked port: RegisterAccount only delegates, and this keeps every test below as it was.
@Import({JacksonConfig.class, RegisterAccount.class})
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountPort accountPort;

    @Test
    void registerReturns201WithLocationHeaderAndNoInternalId() throws Exception {
        Account registered = new Account(
                "seller_payable:123", "owner-1", AccountType.LIABILITY, Currency.BRL, Instant.parse("2026-01-01T00:00:00Z"));
        given(accountPort.register(any())).willReturn(registered);

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterAccountRequest("seller_payable:123", "owner-1", AccountType.LIABILITY))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/accounts/seller_payable:123")))
                .andExpect(jsonPath("$.code").value("seller_payable:123"))
                .andExpect(jsonPath("$.owner_ref").value("owner-1"))
                .andExpect(jsonPath("$.type").value("LIABILITY"))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void findByCodeReturns200ForExistingAccount() throws Exception {
        Account account = new Account(
                "acquirer_fee", null, AccountType.EXPENSE, Currency.BRL, Instant.parse("2026-01-01T00:00:00Z"));
        given(accountPort.findByCode("acquirer_fee")).willReturn(Optional.of(account));

        mockMvc.perform(get("/api/v1/accounts/acquirer_fee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("acquirer_fee"))
                .andExpect(jsonPath("$.owner_ref").doesNotExist());
    }

    @Test
    void findByCodeReturns404ProblemJsonForUnknownCode() throws Exception {
        given(accountPort.findByCode("unknown")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/accounts/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("unknown")));
    }

    @Test
    void registerWithMalformedCodeReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterAccountRequest("Bad Code", null, AccountType.LIABILITY))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("code must be lowercase snake_case")));
    }

    @Test
    void registerWithOverLengthCodeReturns400ProblemJson() throws Exception {
        String tooLong = "a".repeat(101);

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RegisterAccountRequest(tooLong, null, AccountType.LIABILITY))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void registerWithInvalidTypeReturns400ProblemJson() throws Exception {
        String body = """
                {"code":"seller_payable:1","type":"BOGUS"}
                """;

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void registerWithMissingTypeReturns400ProblemJson() throws Exception {
        String body = """
                {"code":"seller_payable:1"}
                """;

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void registerWithOverLengthOwnerRefReturns400ProblemJson() throws Exception {
        String tooLong = "a".repeat(101);

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterAccountRequest("seller_payable:5", tooLong, AccountType.LIABILITY))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("owner_ref must be either omitted/null or non-blank")));
    }

    @Test
    void registerWithBlankOwnerRefReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterAccountRequest("seller_payable:2", "", AccountType.LIABILITY))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("owner_ref must be either omitted/null or non-blank")));
    }

    @Test
    void registerWithUnrecognizedCamelCaseOwnerRefFieldReturns400ProblemJson() throws Exception {
        // Regression test for silently dropping ownerRef (wire format is owner_ref) instead of rejecting it.
        String body = """
                {"code":"seller_payable:4","ownerRef":"owner-1","type":"LIABILITY"}
                """;

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void unexpectedExceptionReturns500ProblemJsonWithoutLeakingInternalMessage() throws Exception {
        given(accountPort.findByCode("boom")).willThrow(new RuntimeException("connection to database failed"));

        mockMvc.perform(get("/api/v1/accounts/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.detail", not(containsString("connection to database failed"))));
    }

    @Test
    void registerWithDuplicateCodeReturns409ProblemJson() throws Exception {
        given(accountPort.register(any())).willThrow(new AccountAlreadyExistsException("seller_payable:3"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterAccountRequest("seller_payable:3", null, AccountType.LIABILITY))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("seller_payable:3")));
    }

    // The request itself is valid; a component below the controller rejecting an argument is a defect in
    // the system, so it is a 500 with the generic detail, never a 400 with the component's own message.
    @Test
    void anIllegalArgumentExceptionFromThePortIs500NotA400() throws Exception {
        given(accountPort.register(any())).willThrow(new IllegalArgumentException("stored row violates an internal invariant"));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterAccountRequest("seller_payable:9", null, AccountType.LIABILITY))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    // ------------------------------------------------------------------ strict JSON: enum names only, no repeats

    // A type must be one of the names. Jackson would otherwise read a string holding a number as a
    // position in the list ("0" -> ASSET); the bare number is already refused by the coercion config.
    @ParameterizedTest
    @ValueSource(strings = {"0", "3"})
    void registerWithTypeGivenAsAQuotedIndexReturns400AndNeverReachesThePort(String index) throws Exception {
        String body = """
                {"code":"seller_payable:1","type":"%s"}
                """.formatted(index);

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").doesNotExist());
        then(accountPort).shouldHaveNoInteractions();
    }

    // ---- an unreadable body names what the client can fix, without replaying what was sent

    private static final String ALLOWED_TYPES = "type must be exactly one of ASSET, LIABILITY, REVENUE, EXPENSE";

    private void registerAndExpectDetail(String body, String detail) throws Exception {
        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(detail));
        then(accountPort).shouldHaveNoInteractions();
    }

    @Test
    void anUnrecognizedFieldIsNamedInTheDetail() throws Exception {
        registerAndExpectDetail(
                "{\"code\":\"seller_payable:1\",\"type\":\"ASSET\",\"nickname\":\"x\"}", "unrecognized field \"nickname\"");
    }

    @Test
    void aCamelCaseOwnerRefIsNamedAsUnrecognizedNotAcceptedAsAnAlias() throws Exception {
        registerAndExpectDetail(
                "{\"code\":\"seller_payable:1\",\"type\":\"ASSET\",\"ownerRef\":\"o-1\"}", "unrecognized field \"ownerRef\"");
    }

    @Test
    void aVeryLongUnrecognizedFieldNameIsCutSoTheDetailDoesNotReplayTheRequest() throws Exception {
        String name = "n".repeat(300);

        registerAndExpectDetail(
                "{\"code\":\"seller_payable:1\",\"type\":\"ASSET\",\"" + name + "\":1}",
                "unrecognized field \"" + "n".repeat(64) + "…\"");
    }

    // Padded, wrong-cased, empty, a position in the list, and a JSON type that is not a string at all: one rule.
    @ParameterizedTest
    @ValueSource(strings = {"\"BOGUS\"", "\" ASSET\"", "\"ASSET \"", "\"asset\"", "\"\"", "\"   \"", "\"0\"", "0", "true", "{}", "[]"})
    void aTypeThatIsNotExactlyOneOfTheNamesIsA400ListingTheAllowedNames(String jsonValue) throws Exception {
        registerAndExpectDetail("{\"code\":\"seller_payable:1\",\"type\":" + jsonValue + "}", ALLOWED_TYPES);
    }

    @Test
    void theRejectedTypeIsNotEchoed() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content("{\"code\":\"seller_payable:1\",\"type\":\"SECRET-VALUE-123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SECRET-VALUE-123"))));
    }

    // Every other cause keeps Spring's generic detail: nothing in it could be named without replaying the input.
    @ParameterizedTest
    @ValueSource(strings = {
        "{",
        "{\"code\":\"seller_payable:1\",\"type\":\"ASSET\",\"type\":\"LIABILITY\"}",
        "{\"code\":12345,\"type\":\"ASSET\"}",
    })
    void otherUnreadableBodiesKeepTheGenericDetail(String body) throws Exception {
        registerAndExpectDetail(body, "Failed to read request");
    }

    // ---- a required field that is missing, null or blank is named, with the request's own field names

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"code\":null,\"type\":null}",
        "{\"code\":\"\",\"type\":null}",
        "{\"code\":\"   \"}",
    })
    void aMissingNullOrBlankCodeAndTypeAreBothNamedInSortedOrder(String body) throws Exception {
        registerAndExpectDetail(body, "code must not be blank; type must not be null");
    }

    @Test
    void aMissingTypeIsNamedAlone() throws Exception {
        registerAndExpectDetail("{\"code\":\"seller_payable:1\"}", "type must not be null");
    }

    @Test
    void anExplicitNullTypeIsNamedAlone() throws Exception {
        registerAndExpectDetail("{\"code\":\"seller_payable:1\",\"type\":null}", "type must not be null");
    }

    @Test
    void aMissingCodeIsNamedAlone() throws Exception {
        registerAndExpectDetail("{\"type\":\"ASSET\"}", "code must not be blank");
    }

    // ---- an owner_ref the database cannot keep as sent is a 400, not a 500 and not a silent change

    // A JSON escape of U+0000, and of an unpaired surrogate (the Java source writes the backslash twice).
    @ParameterizedTest
    @ValueSource(strings = {"a\\u0000b", "\\u0000", "a\\ud83db", "a\\ude00b", "\\ud83d"})
    void anOwnerRefWithANulOrAnUnpairedSurrogateIsA400AndNeverReachesThePort(String escapedOwnerRef) throws Exception {
        registerAndExpectDetail(
                "{\"code\":\"seller_payable:1\",\"type\":\"ASSET\",\"owner_ref\":\"" + escapedOwnerRef + "\"}",
                "owner_ref must not contain a NUL character or an unpaired surrogate: the value could not be stored as sent");
    }

    // ---- no endpoint here reads a query parameter, so any is refused rather than silently dropped. The string
    // is in the URL, not in .param(): MockMvc's .param() sets no query string, which is what the rule looks at.

    @ParameterizedTest
    @ValueSource(strings = {"x=1", "as_of=2026-10-21T15:00:00Z", "owner_ref=sneaked-in"})
    void registerWithAQueryParameterReturns400NamingItAndNeverReachesThePort(String query) throws Exception {
        String name = query.substring(0, query.indexOf('='));

        mockMvc.perform(post("/api/v1/accounts?" + query)
                        .contentType("application/json")
                        .content("{\"code\":\"seller_payable:1\",\"type\":\"LIABILITY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("\"" + name + "\"")))
                .andExpect(jsonPath("$.detail", containsString("accepts no query parameters")));
        then(accountPort).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x=1", "as_of=2026-10-21T15:00:00Z", "AS_OF=2026-10-21T15:00:00Z"})
    void findByCodeWithAQueryParameterReturns400NamingItAndNeverReachesThePort(String query) throws Exception {
        String name = query.substring(0, query.indexOf('='));

        mockMvc.perform(get("/api/v1/accounts/acquirer_fee?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("\"" + name + "\"")));
        then(accountPort).shouldHaveNoInteractions();
    }

    @Test
    void anUnknownParameterIsReportedBeforeAnUnknownAccount() throws Exception {
        given(accountPort.findByCode("unknown")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/accounts/unknown?x=1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("\"x\"")));
        then(accountPort).shouldHaveNoInteractions();
    }

    // Both values are valid names, so the repeat is the only defect: the request must be refused, not
    // resolved to the last one.
    @Test
    void registerWithTypeGivenTwiceReturns400AndNeverReachesThePort() throws Exception {
        String body = """
                {"code":"seller_payable:1","type":"ASSET","type":"LIABILITY"}
                """;

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").doesNotExist());
        then(accountPort).shouldHaveNoInteractions();
    }
}
