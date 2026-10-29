package com.github.josemattioni.ledger_split_engine.adapter.persistence.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The account insert does not send {@code currency} (the application role cannot insert it), so the database
 * default supplies it. That is only correct while the default is every account's currency. This test registers
 * an account for each {@link Currency} value and reads it back: with one value it passes; the day a second
 * value is added without widening the {@code CHECK}, granting {@code INSERT (currency)} and naming the column
 * in the adapter, it fails instead of silently storing the new currency as BRL. It is the executable version of
 * the "what a second currency would take" checklist in PROJECT-CONVENTIONS.md.
 */
@SpringBootTest
class AccountCurrencyRoundTripTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @EnumSource(Currency.class)
    void anAccountComesBackWithTheCurrencyItWasRegisteredWith(Currency currency) {
        String code = uniqueCode("currency_round_trip");

        Account registered = accountPort.register(new Account(code, null, AccountType.ASSET, currency, null));

        assertThat(registered.currency()).as("currency returned by the insert").isEqualTo(currency);
        assertThat(accountPort.findByCode(code))
                .get()
                .extracting(Account::currency)
                .as("currency read back")
                .isEqualTo(currency);
    }
}
