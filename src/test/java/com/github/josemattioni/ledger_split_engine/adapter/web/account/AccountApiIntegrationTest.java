package com.github.josemattioni.ledger_split_engine.adapter.web.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AccountApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String registerBody(String code, String ownerRef, String type) {
        String ownerRefJson = ownerRef == null ? "null" : "\"" + ownerRef + "\"";
        return "{\"code\":\"" + code + "\",\"owner_ref\":" + ownerRefJson + ",\"type\":\"" + type + "\"}";
    }

    @Test
    void registersAndLooksUpAnAccountEndToEnd() throws Exception {
        String code = uniqueCode("seller_payable");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, "owner-1", "LIABILITY")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/accounts/" + code)))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.id").doesNotExist());

        mockMvc.perform(get("/api/v1/accounts/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.owner_ref").value("owner-1"));
    }

    @Test
    void duplicateCodeReturns409ProblemJson() throws Exception {
        String code = uniqueCode("acquirer_fee");
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, null, "EXPENSE")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, null, "EXPENSE")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString(code)));
    }

    @Test
    void malformedCodeReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody("Not A Valid Code", null, "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void invalidTypeReturns400ProblemJson() throws Exception {
        String code = uniqueCode("marketplace_commission");
        String body = "{\"code\":\"" + code + "\",\"type\":\"NOT_A_TYPE\"}";

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void emptyOwnerRefReturns400ProblemJson() throws Exception {
        String code = uniqueCode("seller_payable");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, "", "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void whitespacePaddedOwnerRefReturns400ProblemJson() throws Exception {
        String code = uniqueCode("seller_payable");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, " 123 ", "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // An owner_ref padded with a space that String.trim() does not strip (U+2003, sent as a JSON escape) is padded.
    @ParameterizedTest
    @ValueSource(strings = {"\\u2003123", "123\\u2003", "\\u00A0123", "123\\u3000"})
    void unicodeWhitespacePaddedOwnerRefReturns400NamingTheProblemAndCreatesNothing(String ownerRefJsonEscaped) throws Exception {
        String code = uniqueCode("seller_payable");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, ownerRefJsonEscaped, "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("owner_ref")))
                .andExpect(jsonPath("$.detail", containsString("whitespace")));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    // Jackson alone would trim these into ASSET and register the account: the request said one thing, not another.
    @ParameterizedTest
    @ValueSource(strings = {" ASSET", "ASSET ", "\\tASSET", "asset"})
    void aTypeThatIsNotExactlyAnAccountTypeIsRefusedWithTheAllowedNamesAndCreatesNothing(String type) throws Exception {
        String code = uniqueCode("marketplace_commission");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, null, type)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("type must be exactly one of ASSET, LIABILITY, REVENUE, EXPENSE"));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    @Test
    void anUnrecognizedFieldIsNamedAndCreatesNothing() throws Exception {
        String code = uniqueCode("seller_payable");
        String body = "{\"code\":\"" + code + "\",\"type\":\"LIABILITY\",\"nickname\":\"x\"}";

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("unrecognized field \"nickname\""));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    // PostgreSQL text has no U+0000, and the driver turns an unpaired surrogate into '?': before the domain refused them
    // the first was a 500 and the second stored an account whose owner_ref was not what the client sent.
    @ParameterizedTest
    @ValueSource(strings = {"a\\u0000b", "a\\ud83db", "a\\ude00b"})
    void anOwnerRefTheDatabaseCannotKeepAsSentIs400AndCreatesNothing(String escapedOwnerRef) throws Exception {
        String code = uniqueCode("seller_payable");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, escapedOwnerRef, "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("owner_ref")))
                .andExpect(jsonPath("$.detail", containsString("could not be stored as sent")));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    // The other side of the rule: text the database can keep, outside the Basic Multilingual Plane included, comes back
    // exactly as sent.
    @Test
    void anOwnerRefWithAValidSurrogatePairIsStoredAndReadBackUnchanged() throws Exception {
        String code = uniqueCode("seller_payable");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, "seller-\\ud83d\\ude00-jos\\u00e9", "LIABILITY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner_ref").value("seller-😀-josé"));

        mockMvc.perform(get("/api/v1/accounts/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_ref").value("seller-😀-josé"));
    }

    // 100 emoji are 200 UTF-16 units: counted in characters (as the spec and VARCHAR(100) do) they fit exactly.
    @Test
    void anOwnerRefOfOneHundredEmojiIsStoredAndReadBackUnchanged() throws Exception {
        String code = uniqueCode("seller_payable");
        String emoji = "😀".repeat(100);

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, emoji, "LIABILITY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner_ref").value(emoji));

        mockMvc.perform(get("/api/v1/accounts/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_ref").value(emoji));
    }

    @Test
    void anOwnerRefOfOneHundredAndOneEmojiIs400AndCreatesNothing() throws Exception {
        String code = uniqueCode("seller_payable");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, "😀".repeat(101), "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("at most 100 characters")));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    @Test
    void aRequestWithNoFieldsNamesTheTwoThatAreRequired() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("code must not be blank; type must not be null"));
    }

    // A 200,000-character value used to come back whole in the detail: a 200 KB response for a 200 KB request.
    @Test
    void aHugeOwnerRefGetsAShortDetailNotAnEchoOfItself() throws Exception {
        String code = uniqueCode("seller_payable");

        String response = mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody(code, "o".repeat(200_000), "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("\"" + "o".repeat(64) + "…\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).hasSizeLessThan(600).doesNotContain("o".repeat(65));
    }

    @Test
    void aHugeMalformedCodeGetsAShortDetailNotAnEchoOfItself() throws Exception {
        String response = mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json")
                        .content(registerBody("A".repeat(200_000), null, "LIABILITY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("\"" + "A".repeat(64) + "…\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).hasSizeLessThan(600).doesNotContain("A".repeat(65));
    }

    @Test
    void unknownCodeLookupReturns404ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + uniqueCode("unknown")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void unrecognizedCamelCaseOwnerRefFieldReturns400ProblemJson() throws Exception {
        String code = uniqueCode("seller_payable");
        String body = "{\"code\":\"" + code + "\",\"ownerRef\":\"owner-1\",\"type\":\"LIABILITY\"}";

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void numericOwnerRefReturns400AndCreatesNothing() throws Exception {
        String code = uniqueCode("seller_payable");
        String body = "{\"code\":\"" + code + "\",\"owner_ref\":123,\"type\":\"LIABILITY\"}";

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    @Test
    void numericTypeReturns400AndCreatesNothing() throws Exception {
        String code = uniqueCode("seller_payable");
        String body = "{\"code\":\"" + code + "\",\"type\":0}";

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    // A string holding a number used to register ASSET (the position in the list). Only names are accepted.
    @Test
    void quotedNumericTypeReturns400AndCreatesNothing() throws Exception {
        String code = uniqueCode("seller_payable");
        String body = "{\"code\":\"" + code + "\",\"type\":\"0\"}";

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }

    // Both values are valid names, so the repeat is the only defect: the last one must not win.
    @Test
    void aRepeatedTypeReturns400AndCreatesNothing() throws Exception {
        String code = uniqueCode("seller_payable");
        String body = "{\"code\":\"" + code + "\",\"type\":\"ASSET\",\"type\":\"LIABILITY\"}";

        mockMvc.perform(post("/api/v1/accounts").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/accounts/" + code)).andExpect(status().isNotFound());
    }
}
