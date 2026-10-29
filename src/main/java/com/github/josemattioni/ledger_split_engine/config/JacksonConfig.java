package com.github.josemattioni.ledger_split_engine.config;

import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

/**
 * Makes JSON parsing strict: a request must say exactly one thing, in the JSON type the API documents. Jackson is
 * lenient in ways this class turns off:
 *
 * <ul>
 *   <li>It converts between scalar types whenever it can ({@code "-100"} into a number, {@code 12345} into a string).
 *   <li>It resolves an enum from its position: a bare number is refused by the coercion config below, but a string
 *       holding a number ({@code "type":"0"}) needs {@link EnumFeature#FAIL_ON_NUMBERS_FOR_ENUMS}.
 *   <li>It keeps the last value of a repeated property ({@code "amount_cents":0,"amount_cents":-250} posts -250)
 *       unless {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION} is on.
 * </ul>
 *
 * All three surface as an {@code HttpMessageNotReadableException}, a 400 like any malformed body. A fourth leniency has
 * no switch, so it is replaced: an enum written with padding ({@code "ASSET "}) is trimmed into a valid name.
 * {@link #strictEnums()} registers {@link StrictEnumDeserializer} for every enum in a request body; add new ones
 * there. A {@code @WebMvcTest} slice only gets this configuration if it {@code @Import}s this class.
 */
@Configuration
public class JacksonConfig {

    /** The enums a request body can carry, each read by exact name only. */
    public static SimpleModule strictEnums() {
        return new SimpleModule("strictEnums").addDeserializer(AccountType.class, new StrictEnumDeserializer<>(AccountType.class));
    }

    @Bean
    JsonMapperBuilderCustomizer strictScalarTyping() {
        return builder -> builder
                .addModule(strictEnums())
                .withCoercionConfig(LogicalType.Integer, cfg -> {
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                })
                .withCoercionConfig(LogicalType.Textual, cfg -> {
                    cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                })
                .withCoercionConfig(LogicalType.Enum, cfg -> cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
}
