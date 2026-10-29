package com.github.josemattioni.ledger_split_engine.domain.journalentry;

public class UnbalancedJournalEntryException extends RuntimeException {

    public UnbalancedJournalEntryException(String message) {
        super(message);
    }
}
