package com.github.josemattioni.ledger_split_engine.application.account;

import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountAlreadyExistsException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import org.springframework.stereotype.Service;

/**
 * Registers an account. A write goes through a use case, as the conventions require, even though there is nothing
 * to decide yet: this is where the next rule about registering an account (an account status, an audit entry)
 * belongs, not the controller. It is deliberately not {@code @Transactional}: the adapter's one insert owns its
 * transaction.
 */
@Service
public class RegisterAccount {

    private final AccountPort accountPort;

    public RegisterAccount(AccountPort accountPort) {
        this.accountPort = accountPort;
    }

    /**
     * @return the account as stored, with the values the database assigned (currency, registration time)
     * @throws AccountAlreadyExistsException if an account with that code is already registered
     */
    public Account execute(Account toRegister) {
        return accountPort.register(toRegister);
    }
}
