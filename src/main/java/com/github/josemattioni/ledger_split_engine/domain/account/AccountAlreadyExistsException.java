package com.github.josemattioni.ledger_split_engine.domain.account;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(String code) {
        super("Account with code \"" + code + "\" already exists");
    }
}
