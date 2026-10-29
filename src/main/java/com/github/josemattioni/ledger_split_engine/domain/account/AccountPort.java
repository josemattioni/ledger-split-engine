package com.github.josemattioni.ledger_split_engine.domain.account;

import java.util.Optional;

public interface AccountPort {

    Account register(Account account) throws AccountAlreadyExistsException;

    Optional<Account> findByCode(String code);
}
