package com.github.josemattioni.ledger_split_engine.application.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.AsOfNotSettledException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryPort;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.SettleWindow;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Reads an account's balance, current or as of an instant. A plain read goes to a port directly, but this read
 * applies a ledger rule (an as-of balance is only answered for a settled instant), so it is a use case.
 *
 * <p>The order is part of the contract: the account is looked up first, so an unknown account is a 404 whatever
 * the instant; only then is the instant checked against the database's clock; only then is the sum read. The
 * sum must come from a statement that starts after the clock was read, which is why this is deliberately not
 * {@code @Transactional} and why {@code JournalEntryPort.balanceOf(code, asOf)} refuses to run inside a
 * transaction.
 */
@Service
public class ReadAccountBalance {

    private final AccountPort accountPort;
    private final JournalEntryPort journalEntryPort;

    public ReadAccountBalance(AccountPort accountPort, JournalEntryPort journalEntryPort) {
        this.accountPort = accountPort;
        this.journalEntryPort = journalEntryPort;
    }

    /**
     * @param asOf the instant the balance is as of, or {@code null} for the current balance
     * @throws AccountNotFoundException if no account has that code
     * @throws AsOfNotSettledException if {@code asOf} is later than the database's now minus the settle window
     */
    public AccountBalanceResult execute(String code, Instant asOf) {
        Account account = accountPort.findByCode(code).orElseThrow(() -> new AccountNotFoundException(code));
        if (asOf == null) {
            return new AccountBalanceResult(
                    account.code(), account.currency(), journalEntryPort.balanceOf(account.code()), null);
        }
        Instant databaseNow = journalEntryPort.databaseNow();
        if (!SettleWindow.isSettled(asOf, databaseNow)) {
            throw new AsOfNotSettledException();
        }
        return new AccountBalanceResult(
                account.code(), account.currency(), journalEntryPort.balanceOf(account.code(), asOf), asOf);
    }
}
