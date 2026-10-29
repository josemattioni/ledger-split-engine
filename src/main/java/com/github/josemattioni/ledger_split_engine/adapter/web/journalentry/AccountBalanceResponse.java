package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import java.time.Instant;

/**
 * A balance. {@code asOf} is the instant it is as of, in UTC, or {@code null} (still present in the JSON) for the
 * current balance.
 */
public record AccountBalanceResponse(String accountCode, Currency currency, long balanceCents, Instant asOf) {}
