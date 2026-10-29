package com.github.josemattioni.ledger_split_engine.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTest {

    @ParameterizedTest
    @ValueSource(strings = {"marketplace_commission", "seller_payable:123", "acquirer_fee", "a"})
    void acceptsWellFormedCodes(String code) {
        Account account = new Account(code, null, AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.code()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SellerPayable123",
        "seller payable",
        "1seller_payable",
        "seller_payable:",
        "",
    })
    void rejectsMalformedCodes(String code) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account(code, null, AccountType.LIABILITY, Currency.BRL, null))
                .withMessageContaining("code must be lowercase snake_case");
    }

    @Test
    void rejectsOverLengthCode() {
        String tooLong = "a".repeat(101);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account(tooLong, null, AccountType.LIABILITY, Currency.BRL, null))
                .withMessageContaining("code must be lowercase snake_case");
    }

    @Test
    void allowsNullOwnerRef() {
        Account account = new Account("seller_payable:123", null, AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.ownerRef()).isNull();
    }

    @Test
    void allowsNonBlankOwnerRef() {
        Account account = new Account("seller_payable:123", "123", AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.ownerRef()).isEqualTo("123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 123 ", "123 ", " 123"})
    void rejectsBlankOrPaddedOwnerRef(String ownerRef) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("seller_payable:123", ownerRef, AccountType.LIABILITY, Currency.BRL, null))
                .withMessageContaining("owner_ref must be either omitted/null or non-blank");
    }

    /**
     * {@code String.trim()} strips only characters up to U+0020, so a value padded with any other space passed for
     * unpadded. Each of these is a Unicode White_Space character that trim() leaves in place.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "\u2003123", // em space, leading
        "123\u2003", // em space, trailing
        "\u00A0123", // no-break space, leading
        "123\u00A0", // no-break space, trailing
        "\u3000123\u3000", // ideographic space, both ends
        "\u0085123", // next line
        "123\u2028", // line separator
        "\u2003", // only an em space: blank
        "\u00A0\u00A0",
    })
    void rejectsOwnerRefPaddedWithUnicodeWhitespace(String ownerRef) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("seller_payable:123", ownerRef, AccountType.LIABILITY, Currency.BRL, null))
                .withMessageContaining("owner_ref must be either omitted/null or non-blank")
                .withMessageContaining("whitespace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme corp", "acme\u2003corp", "acme\u00A0corp", "a\tb"})
    void allowsWhitespaceInsideAnOwnerRef(String ownerRef) {
        Account account = new Account("seller_payable:123", ownerRef, AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.ownerRef()).isEqualTo(ownerRef);
    }

    /**
     * What the database cannot keep as sent: PostgreSQL text has no U+0000 (the insert fails), and the driver turns an
     * unpaired surrogate into {@code ?}. Both used to get through (a 500, and an account stored with a value nobody sent).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "a\u0000b",
        "\u0000",
        "abc\u0000",
        "\u0000abc",
        "a\ud83db", // a high surrogate with nothing after it
        "a\ud83d", // ... at the very end
        "a\ude00b", // a low surrogate with nothing before it
        "\ud83d😀", // a high surrogate in front of a valid pair
        "\ude00\ud83d", // low then high, the wrong way round
    })
    void rejectsOwnerRefTheDatabaseCannotStoreAsSent(String ownerRef) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("seller_payable:123", ownerRef, AccountType.LIABILITY, Currency.BRL, null))
                .withMessage("owner_ref must not contain a NUL character or an unpaired surrogate: the value could not be stored as sent");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "seller-😀", // a well-formed surrogate pair: an emoji
        "😀😀",
        "josé",
        "日本語",
        "a\u0001b", // control characters other than NUL are storable, and an owner_ref is opaque
        "a\tb",
    })
    void allowsOwnerRefTheDatabaseCanStoreAsSent(String ownerRef) {
        Account account = new Account("seller_payable:123", ownerRef, AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.ownerRef()).isEqualTo(ownerRef);
    }

    // A value of 200,000 characters used to be repeated whole in the detail: a 200 KB response for a 200 KB request.
    @Test
    void aHugeOwnerRefIsNamedByItsFirstSixtyFourCharactersOnly() {
        String huge = "o".repeat(200_000);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("seller_payable:123", huge, AccountType.LIABILITY, Currency.BRL, null))
                .withMessage("owner_ref must be either omitted/null or non-blank with no leading/trailing whitespace"
                        + " and at most 100 characters: \"" + "o".repeat(64) + "…\"");
    }

    @Test
    void aHugeMalformedCodeIsNamedByItsFirstSixtyFourCharactersOnly() {
        String huge = "A".repeat(200_000);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account(huge, null, AccountType.LIABILITY, Currency.BRL, null))
                .withMessage("code must be " + AccountCode.SHAPE + ": \"" + "A".repeat(64) + "…\"");
    }

    @Test
    void aShortRejectedValueIsStillNamedWhole() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("Seller Payable", null, AccountType.LIABILITY, Currency.BRL, null))
                .withMessage("code must be " + AccountCode.SHAPE + ": \"Seller Payable\"");
    }

    // The length is in characters, as the spec says and as VARCHAR(100) counts: an emoji is one character, two UTF-16 units.
    @Test
    void allowsAnOwnerRefOfExactlyTheMaximumLengthInCharactersOutsideTheBasicPlane() {
        String emoji = "😀".repeat(100);

        Account account = new Account("seller_payable:123", emoji, AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.ownerRef()).isEqualTo(emoji);
    }

    @Test
    void rejectsAnOwnerRefOneCharacterOverTheMaximumLengthOutsideTheBasicPlane() {
        String emoji = "😀".repeat(101);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("seller_payable:123", emoji, AccountType.LIABILITY, Currency.BRL, null))
                .withMessageContaining("at most 100 characters");
    }

    @Test
    void allowsFiftyOneCharactersOutsideTheBasicPlaneThatWereOnceRefusedAsOneHundredAndTwoUnits() {
        String emoji = "😀".repeat(51);

        Account account = new Account("seller_payable:123", emoji, AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.ownerRef()).isEqualTo(emoji);
    }

    @Test
    void allowsOwnerRefAtMaxLength() {
        String maxLength = "a".repeat(100);

        Account account = new Account("seller_payable:123", maxLength, AccountType.LIABILITY, Currency.BRL, null);

        assertThat(account.ownerRef()).isEqualTo(maxLength);
    }

    @Test
    void rejectsOverLengthOwnerRef() {
        String tooLong = "a".repeat(101);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Account("seller_payable:123", tooLong, AccountType.LIABILITY, Currency.BRL, null))
                .withMessageContaining("owner_ref must be either omitted/null or non-blank");
    }
}
