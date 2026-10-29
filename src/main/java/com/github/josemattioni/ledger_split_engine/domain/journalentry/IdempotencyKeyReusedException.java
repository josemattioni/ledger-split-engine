package com.github.josemattioni.ledger_split_engine.domain.journalentry;

/**
 * An {@code Idempotency-Key} that already identifies an entry was sent with different lines. The
 * message names the key and deliberately nothing about the stored entry.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key \"" + idempotencyKey + "\" was already used for a different request");
    }
}
