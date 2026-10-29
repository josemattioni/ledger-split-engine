package com.github.josemattioni.ledger_split_engine.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountAlreadyExistsException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * The registration use case, with a mocked port and no Spring: it hands the account to the port and returns what
 * the port stored (with the currency and registration time the database assigned), and lets a duplicate code
 * through unchanged for the web layer to report as 409.
 */
class RegisterAccountTest {

    private final AccountPort port = mock(AccountPort.class);
    private final RegisterAccount useCase = new RegisterAccount(port);

    private static final Account TO_REGISTER =
            new Account("seller_payable:123", "owner-1", AccountType.LIABILITY, Currency.BRL, null);

    @Test
    void itRegistersThroughThePortAndReturnsTheStoredAccount() {
        Account stored = new Account(
                "seller_payable:123", "owner-1", AccountType.LIABILITY, Currency.BRL, Instant.parse("2026-10-26T12:00:00Z"));
        given(port.register(TO_REGISTER)).willReturn(stored);

        Account result = useCase.execute(TO_REGISTER);

        assertThat(result).isSameAs(stored);
        verify(port).register(TO_REGISTER);
        verifyNoMoreInteractions(port);
    }

    @Test
    void aDuplicateCodeIsNotCaughtOrChanged() {
        AccountAlreadyExistsException duplicate = new AccountAlreadyExistsException("seller_payable:123");
        given(port.register(TO_REGISTER)).willThrow(duplicate);

        assertThatThrownBy(() -> useCase.execute(TO_REGISTER)).isSameAs(duplicate);
    }

    // The adapter's single insert owns its transaction (the conventions: a use case is not @Transactional by default).
    @Test
    void itIsNotTransactional() {
        assertThat(RegisterAccount.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(RegisterAccount.class.getDeclaredMethods())
                .allSatisfy(method -> assertThat(method.isAnnotationPresent(Transactional.class)).isFalse());
    }
}
