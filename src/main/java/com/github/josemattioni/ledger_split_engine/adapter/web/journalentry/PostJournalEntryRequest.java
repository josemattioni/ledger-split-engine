package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PostJournalEntryRequest(@NotNull List<@NotNull @Valid LineRequest> lines) {

    public record LineRequest(@NotBlank String accountCode, @NotNull Long amountCents) {}
}
