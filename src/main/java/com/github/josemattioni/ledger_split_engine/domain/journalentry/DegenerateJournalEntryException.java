package com.github.josemattioni.ledger_split_engine.domain.journalentry;

/**
 * A journal entry that is not a real movement of money: fewer than two lines, a zero-amount line,
 * or the same account on more than one line. One type for the three cases; the message says which.
 */
public class DegenerateJournalEntryException extends RuntimeException {

    private DegenerateJournalEntryException(String message) {
        super(message);
    }

    static DegenerateJournalEntryException tooFewLines(int lineCount) {
        return new DegenerateJournalEntryException(
                "journal entry must have at least two lines (got " + lineCount + ")");
    }

    static DegenerateJournalEntryException zeroAmount(int lineNumber) {
        return new DegenerateJournalEntryException("line " + lineNumber + " has a zero amount_cents");
    }

    static DegenerateJournalEntryException repeatedAccount(String accountCode, int firstLine, int secondLine) {
        return new DegenerateJournalEntryException("account_code " + accountCode
                + " appears on more than one line (lines " + firstLine + " and " + secondLine + ")");
    }
}
