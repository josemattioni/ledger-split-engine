package com.github.josemattioni.ledger_split_engine.domain;

/**
 * A value the client sent, as it may appear in an error detail. The detail identifies what was wrong; it does not
 * replay the request, so a value past {@value #MAX_CODE_POINTS} characters is cut there and marked with an ellipsis
 * (a 200,000-character {@code owner_ref} must not produce a 200 KB response). A shorter value is repeated whole.
 *
 * <p>The cut counts code points, so it never splits a surrogate pair. Use it for values that were not validated
 * before they are echoed; a value already known to be short (a key of at most 255 characters that passed its shape
 * check, whose reuse is reported by name) is not worth cutting.
 */
public final class EchoedValue {

    public static final int MAX_CODE_POINTS = 64;

    private static final String ELLIPSIS = "…";

    private EchoedValue() {}

    /** The value, or its first {@value #MAX_CODE_POINTS} characters and an ellipsis; {@code null} reads as {@code null}. */
    public static String excerpt(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= MAX_CODE_POINTS || value.codePointCount(0, value.length()) <= MAX_CODE_POINTS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_CODE_POINTS)) + ELLIPSIS;
    }

    /** {@link #excerpt} between double quotes. */
    public static String quoted(String value) {
        return "\"" + excerpt(value) + "\"";
    }
}
