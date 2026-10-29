package com.github.josemattioni.ledger_split_engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * An enum in a request body is read by its exact name and by nothing else. Jackson's own enum deserializer retries
 * with the text trimmed, so a padded value used to be stored as the unpadded one; there is no feature to turn that
 * off, which is why the deserializer is replaced.
 */
class StrictEnumDeserializerTest {

    /** The mapper as the application builds it for enums: the real module, and nothing else of the configuration. */
    private final JsonMapper mapper = JsonMapper.builder().addModule(JacksonConfig.strictEnums()).build();

    private AccountType read(String json) {
        return mapper.readValue(json, AccountType.class);
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void everyExactNameIsRead(AccountType type) {
        assertThat(read("\"" + type.name() + "\"")).isEqualTo(type);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\" ASSET\"", // padded: Jackson's own deserializer would have trimmed it
        "\"ASSET \"",
        "\"\\tASSET\"",
        "\"ASSET\\n\"",
        "\"\\u00A0ASSET\"",
        "\"asset\"", // wrong case
        "\"Asset\"",
        "\"BOGUS\"",
        "\"\"",
        "\"   \"",
        "\"0\"", // a string holding a position in the list
        "\"3\"",
        "0", // a bare number
        "1.5",
        "true",
        "{\"name\":\"ASSET\"}",
        "[\"ASSET\"]",
    })
    void anythingElseIsAMismatchForTheEnumType(String json) {
        assertThatThrownBy(() -> read(json))
                .isInstanceOfSatisfying(MismatchedInputException.class, e -> assertThat(e.getTargetType()).isEqualTo(AccountType.class));
    }
}
