package com.github.josemattioni.ledger_split_engine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * An error detail identifies a rejected value, it does not replay it: past 64 characters the value is cut and marked.
 * A value of 64 or fewer is repeated whole, so every existing assertion on a short value keeps passing.
 */
class EchoedValueTest {

    @Test
    void aValueOf63And64CharactersIsRepeatedWhole() {
        assertThat(EchoedValue.excerpt("a".repeat(63))).isEqualTo("a".repeat(63));
        assertThat(EchoedValue.excerpt("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    @Test
    void aValueOf65CharactersIsCutAtSixtyFourAndMarked() {
        assertThat(EchoedValue.excerpt("a".repeat(65))).isEqualTo("a".repeat(64) + "…");
    }

    @Test
    void aHugeValueGivesAnExcerptOfBoundedLength() {
        assertThat(EchoedValue.excerpt("x".repeat(200_000))).hasSize(65);
    }

    @Test
    void theCutKeepsTheStartOfTheValue() {
        String value = "0123456789".repeat(10);

        assertThat(EchoedValue.excerpt(value)).isEqualTo(value.substring(0, 64) + "…");
    }

    // Length is in characters, not UTF-16 units: 64 emoji are 128 units and still whole.
    @Test
    void sixtyFourCharactersOutsideTheBasicPlaneAreRepeatedWhole() {
        String emoji = "😀".repeat(64);

        assertThat(EchoedValue.excerpt(emoji)).isEqualTo(emoji);
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        String emoji = "😀".repeat(65);

        String excerpt = EchoedValue.excerpt(emoji);

        assertThat(excerpt).isEqualTo("😀".repeat(64) + "…");
        // A split pair would leave a lone surrogate, which shows up as a code point in the surrogate range.
        assertThat(excerpt.codePoints().filter(cp -> cp >= 0xD800 && cp <= 0xDFFF).count()).isZero();
    }

    @Test
    void aPairStraddlingTheCutIsKeptOrDroppedAsAWholeNotHalved() {
        // 63 plain characters, then an emoji (a pair), then more: the emoji is the 64th character and stays whole.
        String value = "a".repeat(63) + "😀" + "tail";

        assertThat(EchoedValue.excerpt(value)).isEqualTo("a".repeat(63) + "😀…");
    }

    @Test
    void anEmptyValueAndNullAreHandled() {
        assertThat(EchoedValue.excerpt("")).isEmpty();
        assertThat(EchoedValue.excerpt(null)).isEqualTo("null");
    }

    @Test
    void quotedWrapsTheExcerptInDoubleQuotes() {
        assertThat(EchoedValue.quoted("abc")).isEqualTo("\"abc\"");
        assertThat(EchoedValue.quoted("a".repeat(65))).isEqualTo("\"" + "a".repeat(64) + "…\"");
        assertThat(EchoedValue.quoted(null)).isEqualTo("\"null\"");
    }
}
