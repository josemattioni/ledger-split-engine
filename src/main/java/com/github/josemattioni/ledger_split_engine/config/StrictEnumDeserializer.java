package com.github.josemattioni.ledger_split_engine.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Reads an enum from its exact name and nothing else. Jackson's own enum deserializer retries with the text trimmed
 * when it is not a name, so {@code " ASSET"} would become {@code ASSET}; this API rejects padded values everywhere
 * else, and a request that says one thing must not be stored as another. Anything that is not an exact name fails with
 * the usual mismatched-input exception, which {@code ApiExceptionHandler} reports with the allowed values. Register
 * one per enum that can appear in a request body ({@link JacksonConfig}).
 */
final class StrictEnumDeserializer<E extends Enum<E>> extends StdDeserializer<E> {

    private final Class<E> type;

    StrictEnumDeserializer(Class<E> type) {
        super(type);
        this.type = type;
    }

    @Override
    public E deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return type.cast(context.handleUnexpectedToken(type, parser));
        }
        String text = parser.getString();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) {
                return constant;
            }
        }
        throw context.weirdStringException(text, type, "not one of the names of " + type.getSimpleName());
    }
}
