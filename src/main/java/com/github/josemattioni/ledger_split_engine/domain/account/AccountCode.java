package com.github.josemattioni.ledger_split_engine.domain.account;

import java.util.regex.Pattern;

/**
 * The shape of an account's {@code code}: a lowercase snake_case prefix with an optional
 * {@code :identifier} suffix, at most {@value #MAX_LENGTH} characters. This is the one Java source of
 * that rule. It validates a code being registered and every code named on a journal line; the
 * {@code accounts.code} CHECK is its anchored SQL mirror.
 *
 * <p>Comparison is exact: no trimming and no case-folding.
 */
public final class AccountCode {

    public static final int MAX_LENGTH = 100;

    /** What a well-formed code looks like, worded to follow "must be" in an error message. */
    public static final String SHAPE = "lowercase snake_case, optionally followed by \":identifier\" (max "
            + MAX_LENGTH + " characters) — e.g. \"seller_payable:123\"";

    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(:[a-z0-9_-]+)?$");

    private AccountCode() {}

    public static boolean isWellFormed(String code) {
        return code != null && code.length() <= MAX_LENGTH && PATTERN.matcher(code).matches();
    }
}
