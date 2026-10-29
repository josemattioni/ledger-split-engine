package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import com.github.josemattioni.ledger_split_engine.adapter.web.AcceptedQueryParameters;
import com.github.josemattioni.ledger_split_engine.adapter.web.InvalidRequestException;
import com.github.josemattioni.ledger_split_engine.application.journalentry.AccountBalanceResult;
import com.github.josemattioni.ledger_split_engine.application.journalentry.ReadAccountBalance;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountBalanceController {

    private final ReadAccountBalance readAccountBalance;

    public AccountBalanceController(ReadAccountBalance readAccountBalance) {
        this.readAccountBalance = readAccountBalance;
    }

    /**
     * {@code as_of} is read from the request itself, not bound to a parameter, so that a repeated or empty one can be
     * rejected rather than split or defaulted (see {@link AsOfParameter}); only that reading is guarded, and the use
     * case runs outside it, so its own failures stay internal errors. Any other query parameter, and a malformed
     * encoding, were already answered with a 400 by {@code QueryParameterInterceptor}.
     */
    @GetMapping("/{code}/balance")
    @AcceptedQueryParameters(AsOfParameter.NAME)
    public AccountBalanceResponse balanceOf(@PathVariable String code, HttpServletRequest request) {
        Instant asOf = InvalidRequestException.guard(() -> AsOfParameter.from(request));

        AccountBalanceResult result = readAccountBalance.execute(code, asOf);

        return new AccountBalanceResponse(result.accountCode(), result.currency(), result.balanceCents(), result.asOf());
    }
}
