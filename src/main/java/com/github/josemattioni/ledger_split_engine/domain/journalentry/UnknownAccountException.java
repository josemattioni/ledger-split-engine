package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import java.util.Set;

public class UnknownAccountException extends RuntimeException {

    public UnknownAccountException(Set<String> unknownAccountCodes) {
        super("Unknown account code(s): " + String.join(", ", unknownAccountCodes.stream().sorted().toList()));
    }
}
