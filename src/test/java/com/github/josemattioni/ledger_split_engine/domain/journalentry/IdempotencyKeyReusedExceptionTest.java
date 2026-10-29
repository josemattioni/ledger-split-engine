package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdempotencyKeyReusedExceptionTest {

    @Test
    void messageNamesTheKeyAndNothingElse() {
        IdempotencyKeyReusedException exception = new IdempotencyKeyReusedException("sale-9a7f");

        // The constructor takes only the key, so the stored entry's account codes and amounts cannot
        // reach the message; the exact text also keeps class and package names out of an API `detail`.
        assertThat(exception.getMessage())
                .isEqualTo("Idempotency-Key \"sale-9a7f\" was already used for a different request");
    }

    // The key was accepted as well-formed, so it is at most 255 characters; it is named in full, not cut like an
    // unvalidated value (the contract suite asserts exactly this).
    @Test
    void aKeyOfTheMaximumLengthIsNamedInFull() {
        String key = "k".repeat(IdempotencyKey.MAX_LENGTH);

        assertThat(new IdempotencyKeyReusedException(key).getMessage()).contains(key).doesNotContain("…");
    }
}
