package com.github.josemattioni.ledger_split_engine.adapter.web.account;

import com.github.josemattioni.ledger_split_engine.adapter.web.InvalidRequestException;
import com.github.josemattioni.ledger_split_engine.application.account.RegisterAccount;
import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final RegisterAccount registerAccount;
    private final AccountPort accountPort;

    public AccountController(RegisterAccount registerAccount, AccountPort accountPort) {
        this.registerAccount = registerAccount;
        this.accountPort = accountPort;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> register(@Valid @RequestBody RegisterAccountRequest request) {
        Account toRegister = InvalidRequestException.guard(
                () -> new Account(request.code(), request.ownerRef(), request.type(), Currency.BRL, null));
        Account registered = registerAccount.execute(toRegister);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{code}")
                .buildAndExpand(registered.code())
                .toUri();

        return ResponseEntity.created(location).body(AccountResponse.from(registered));
    }

    @GetMapping("/{code}")
    public AccountResponse findByCode(@PathVariable String code) {
        return accountPort
                .findByCode(code)
                .map(AccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(code));
    }
}
