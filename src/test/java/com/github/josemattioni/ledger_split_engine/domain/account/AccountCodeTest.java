package com.github.josemattioni.ledger_split_engine.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"marketplace_commission", "seller_payable:123", "acquirer_fee", "a", "seller_payable:ab-c_9"})
    void acceptsWellFormedCodes(String code) {
        assertThat(AccountCode.isWellFormed(code)).isTrue();
    }

    // Each malformed code differs from a well-formed one in exactly one way.
    @Test
    void rejectsUppercase() {
        assertThat(AccountCode.isWellFormed("Seller_payable:123")).isFalse();
    }

    @Test
    void rejectsALeadingSpace() {
        assertThat(AccountCode.isWellFormed(" seller_payable:123")).isFalse();
    }

    @Test
    void rejectsATrailingSpace() {
        assertThat(AccountCode.isWellFormed("seller_payable:123 ")).isFalse();
    }

    @Test
    void rejectsALeadingDigit() {
        assertThat(AccountCode.isWellFormed("1seller_payable")).isFalse();
    }

    // A hyphen is only allowed in the identifier after the colon, not in the snake_case prefix.
    @Test
    void rejectsAHyphenWithoutAColonSuffix() {
        assertThat(AccountCode.isWellFormed("seller-payable")).isFalse();
    }

    @Test
    void rejectsATrailingNewline() {
        assertThat(AccountCode.isWellFormed("seller_payable\n")).isFalse();
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        assertThat(AccountCode.isWellFormed("a".repeat(AccountCode.MAX_LENGTH))).isTrue();
    }

    @Test
    void rejectsOneCharacterOverTheMaximumLength() {
        assertThat(AccountCode.isWellFormed("a".repeat(AccountCode.MAX_LENGTH + 1))).isFalse();
    }

    @Test
    void nullIsNotWellFormed() {
        assertThat(AccountCode.isWellFormed(null)).isFalse();
    }

    // Account registration now builds its message from SHAPE; it must read exactly as it did when the
    // text was written inline.
    @Test
    void accountRegistrationReportsTheShapeInTheSameWordsAsBefore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("BAD", null, AccountType.LIABILITY, Currency.BRL, null))
                .withMessage("code must be lowercase snake_case, optionally followed by \":identifier\" "
                        + "(max 100 characters) — e.g. \"seller_payable:123\": \"BAD\"");
    }
}
