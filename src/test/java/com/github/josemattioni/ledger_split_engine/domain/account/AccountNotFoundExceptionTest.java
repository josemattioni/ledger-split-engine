package com.github.josemattioni.ledger_split_engine.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountNotFoundExceptionTest {

    @Test
    void theMessageNamesTheCodeInTheWordsTheBalanceLookupAlwaysUsed() {
        assertThat(new AccountNotFoundException("seller_payable:1").getMessage())
                .isEqualTo("No account with code \"seller_payable:1\"");
    }

    @Test
    void itIsNotAnIllegalArgumentExceptionSoTheWebLayerDoesNotTakeItForAMalformedRequest() {
        assertThat(new AccountNotFoundException("x")).isNotInstanceOf(IllegalArgumentException.class);
    }
}
