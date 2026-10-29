package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import java.util.Set;

public class MixedCurrencyException extends RuntimeException {

    public MixedCurrencyException(Set<String> currencies) {
        super("Journal entry lines reference accounts of more than one currency: "
                + String.join(", ", currencies.stream().sorted().toList()));
    }
}
