package com.github.josemattioni.ledger_split_engine.adapter.web;

import java.io.IOException;

/**
 * A request body went past the limit while it was being read ({@link RequestBodyLimitFilter}). An
 * {@code IOException} on purpose: it travels up through the JSON reader like any failed read, and
 * {@code ApiExceptionHandler} finds it in the cause chain and answers 413. The message carries the limit and
 * nothing of the request.
 */
public class RequestBodyTooLargeException extends IOException {

    RequestBodyTooLargeException(long limit) {
        super("the request body is larger than " + limit + " bytes");
    }
}
