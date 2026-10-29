package com.github.josemattioni.ledger_split_engine.adapter.web.account;

import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterAccountRequest(
        @NotBlank String code,
        String ownerRef,
        @NotNull AccountType type) {
}
