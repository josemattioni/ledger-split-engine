package com.github.josemattioni.ledger_split_engine.adapter.web.account;

import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import java.time.Instant;

public record AccountResponse(
        String code,
        String ownerRef,
        AccountType type,
        Currency currency,
        Instant createdAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(
                account.code(), account.ownerRef(), account.type(), account.currency(), account.createdAt());
    }
}
