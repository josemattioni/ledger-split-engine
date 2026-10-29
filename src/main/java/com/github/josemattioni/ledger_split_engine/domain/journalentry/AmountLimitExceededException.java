package com.github.josemattioni.ledger_split_engine.domain.journalentry;

public class AmountLimitExceededException extends RuntimeException {

    public AmountLimitExceededException(int lineNumber, long amountCents) {
        super("line " + lineNumber + ": amount_cents " + amountCents + " is outside the permitted range of -"
                + JournalEntry.MAX_ABS_AMOUNT_CENTS + " to " + JournalEntry.MAX_ABS_AMOUNT_CENTS);
    }
}
