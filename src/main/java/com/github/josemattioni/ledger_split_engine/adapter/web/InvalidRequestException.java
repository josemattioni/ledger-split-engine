package com.github.josemattioni.ledger_split_engine.adapter.web;

import java.util.function.Supplier;

/**
 * The request was wrong: answered with 400 and this message as the detail.
 *
 * <p>The domain constructors report a structural defect with an {@link IllegalArgumentException}, but they also run on
 * rows read back from the database, where the same exception means the system is at fault. The type cannot tell the
 * two apart; the call site can. So a controller wraps exactly the construction of domain objects from the request in
 * {@link #guard}, and any other {@code IllegalArgumentException} falls through to the generic 500. The domain's
 * ledger-rule exceptions (422) are not {@code IllegalArgumentException}s, so {@link #guard} leaves them alone.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    private InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Runs {@code construction}, which builds domain objects from the request, and reports an
     * {@link IllegalArgumentException} from it as a client error.
     */
    public static <T> T guard(Supplier<T> construction) {
        try {
            return construction.get();
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage(), e);
        }
    }
}
