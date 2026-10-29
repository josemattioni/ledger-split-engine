package com.github.josemattioni.ledger_split_engine.application.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import java.time.Instant;

/**
 * What {@link ReadAccountBalance} found: the account's code and currency, the signed sum, and the instant the
 * sum is as of ({@code null} for the current balance).
 */
public record AccountBalanceResult(String accountCode, Currency currency, long balanceCents, Instant asOf) {}
