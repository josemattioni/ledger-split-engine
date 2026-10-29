package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.EchoedValue;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountCode;

/**
 * A journal line names an account code that could not belong to any account, because it does not have
 * the shape a registered code must have. A ledger rule (422), so it deliberately does not extend
 * {@link IllegalArgumentException}, which the web layer treats as a malformed request. A well-formed
 * code that matches no account is a different failure ({@link UnknownAccountException}).
 */
public class InvalidAccountCodeException extends RuntimeException {

    public InvalidAccountCodeException(int lineNumber, String accountCode) {
        super("line " + lineNumber + ": account_code " + EchoedValue.quoted(accountCode) + " must be " + AccountCode.SHAPE);
    }
}
