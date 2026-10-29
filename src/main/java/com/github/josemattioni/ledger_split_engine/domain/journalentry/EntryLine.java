package com.github.josemattioni.ledger_split_engine.domain.journalentry;

public record EntryLine(int lineNumber, String accountCode, long amountCents) {

    public EntryLine {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("line_number must be at least 1: " + lineNumber);
        }
        if (accountCode == null) {
            throw new IllegalArgumentException("account_code must not be null");
        }
    }
}
