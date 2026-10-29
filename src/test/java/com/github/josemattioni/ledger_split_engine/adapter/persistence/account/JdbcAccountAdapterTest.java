package com.github.josemattioni.ledger_split_engine.adapter.persistence.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountAlreadyExistsException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JdbcAccountAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcAccountAdapter adapter;

    @Test
    void registersAndFindsAccountByCode() {
        String code = uniqueCode("seller_payable");
        Account toRegister = new Account(code, "owner-1", AccountType.LIABILITY, Currency.BRL, null);

        Account registered = adapter.register(toRegister);

        assertThat(registered.code()).isEqualTo(code);
        assertThat(registered.ownerRef()).isEqualTo("owner-1");
        assertThat(registered.type()).isEqualTo(AccountType.LIABILITY);
        assertThat(registered.currency()).isEqualTo(Currency.BRL);
        assertThat(registered.createdAt()).isNotNull();

        assertThat(adapter.findByCode(code)).contains(registered);
    }

    @Test
    void registersAccountWithoutOwnerRef() {
        String code = uniqueCode("marketplace_commission");

        Account registered = adapter.register(new Account(code, null, AccountType.REVENUE, Currency.BRL, null));

        assertThat(registered.ownerRef()).isNull();
    }

    @Test
    void findByCodeReturnsEmptyWhenNotFound() {
        assertThat(adapter.findByCode(uniqueCode("unknown"))).isEmpty();
    }

    @Test
    void registeringDuplicateCodeThrowsAccountAlreadyExistsException() {
        String code = uniqueCode("acquirer_fee");
        adapter.register(new Account(code, null, AccountType.EXPENSE, Currency.BRL, null));

        assertThatThrownBy(() -> adapter.register(new Account(code, null, AccountType.EXPENSE, Currency.BRL, null)))
                .isInstanceOf(AccountAlreadyExistsException.class)
                .hasMessageContaining(code);
    }
}
