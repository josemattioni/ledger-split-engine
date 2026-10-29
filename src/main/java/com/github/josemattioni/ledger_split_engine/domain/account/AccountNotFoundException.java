package com.github.josemattioni.ledger_split_engine.domain.account;

/**
 * No account has the requested {@code code}. Answered with 404; it does not extend
 * {@link IllegalArgumentException}, which the web layer treats as a malformed request. The message is the one a
 * balance lookup has always carried, so the detail the client sees did not change when the lookup moved from
 * the controller into a use case.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String code) {
        super("No account with code \"" + code + "\"");
    }
}
