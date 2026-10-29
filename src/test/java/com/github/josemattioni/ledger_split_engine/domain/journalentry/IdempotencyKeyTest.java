package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    void acceptsASingleCharacterKey() {
        assertThat(IdempotencyKey.isWellFormed("a")).isTrue();
    }

    @Test
    void acceptsAKeyOfExactlyTheMaximumLength() {
        assertThat(IdempotencyKey.isWellFormed("k".repeat(IdempotencyKey.MAX_LENGTH))).isTrue();
    }

    @Test
    void acceptsEveryVisibleAsciiCharacterIncludingPunctuation() {
        assertThat(IdempotencyKey.isWellFormed("sale-4711:reversal/of!#$%&'()*+,.;<=>?@[]^_`{|}~")).isTrue();
    }

    // Each rejected key differs from a well-formed one in exactly one way.

    @Test
    void rejectsAnEmptyKey() {
        assertThat(IdempotencyKey.isWellFormed("")).isFalse();
    }

    @Test
    void rejectsOneCharacterOverTheMaximumLength() {
        assertThat(IdempotencyKey.isWellFormed("k".repeat(IdempotencyKey.MAX_LENGTH + 1))).isFalse();
    }

    @Test
    void rejectsASpace() {
        assertThat(IdempotencyKey.isWellFormed("sale 4711")).isFalse();
    }

    @Test
    void rejectsATab() {
        assertThat(IdempotencyKey.isWellFormed("sale\t4711")).isFalse();
    }

    @Test
    void rejectsAControlCharacter() {
        assertThat(IdempotencyKey.isWellFormed("sale\u00014711")).isFalse();
    }

    @Test
    void rejectsATrailingNewline() {
        assertThat(IdempotencyKey.isWellFormed("sale-4711\n")).isFalse();
    }

    @Test
    void rejectsANonAsciiLetter() {
        assertThat(IdempotencyKey.isWellFormed("venda-é")).isFalse();
    }

    @Test
    void nullIsNotWellFormed() {
        assertThat(IdempotencyKey.isWellFormed(null)).isFalse();
    }

    // JournalEntry used to build this message inline; the text must not change.
    @Test
    void requireWellFormedReportsTheKeyInTheSameWordsAsBefore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> IdempotencyKey.requireWellFormed("bad key"))
                .withMessage("idempotency_key must be 1-255 visible ASCII characters (no whitespace): \"bad key\"");
    }

    @Test
    void requireWellFormedAcceptsAWellFormedKeyWithoutThrowing() {
        IdempotencyKey.requireWellFormed("sale-4711");
    }
}
