package com.github.josemattioni.ledger_split_engine.adapter.web;

import java.net.URI;

/**
 * The problem {@code type} of each 422 ledger rule, so a client (or a test) can tell which rule
 * fired from more than the status code. URNs rather than URLs: the project owns no domain, and a
 * made-up {@code https://} URL would look dereferenceable and isn't.
 */
final class ProblemTypes {

    private static final String PREFIX = "urn:ledger-split-engine:problem:";

    static final URI UNBALANCED_ENTRY = URI.create(PREFIX + "unbalanced-entry");
    static final URI DEGENERATE_ENTRY = URI.create(PREFIX + "degenerate-entry");
    static final URI UNKNOWN_ACCOUNT = URI.create(PREFIX + "unknown-account");
    static final URI MIXED_CURRENCY = URI.create(PREFIX + "mixed-currency");
    static final URI AMOUNT_LIMIT_EXCEEDED = URI.create(PREFIX + "amount-limit-exceeded");
    static final URI IDEMPOTENCY_KEY_REUSED = URI.create(PREFIX + "idempotency-key-reused");
    static final URI LINE_LIMIT_EXCEEDED = URI.create(PREFIX + "line-limit-exceeded");
    static final URI INVALID_ACCOUNT_CODE = URI.create(PREFIX + "invalid-account-code");
    static final URI CANNOT_REVERSE_A_REVERSAL = URI.create(PREFIX + "cannot-reverse-a-reversal");
    static final URI ENTRY_ALREADY_REVERSED = URI.create(PREFIX + "entry-already-reversed");
    static final URI AS_OF_NOT_SETTLED = URI.create(PREFIX + "as-of-not-settled");

    private ProblemTypes() {}
}
