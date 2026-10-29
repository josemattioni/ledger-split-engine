package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The query string as the application reads it: from the query string alone, with strict UTF-8. Every case is one
 * defect; the two fixed details repeat nothing the client sent.
 */
class QueryStringTest {

    private static final String MALFORMED = "the query string is not valid percent-encoded UTF-8";
    private static final String NAMELESS = "the query string has a parameter with no name";

    private static QueryString parse(String raw) {
        return QueryString.parse(raw);
    }

    private static String refusalOf(String raw) {
        Throwable thrown = catchThrowable(() -> parse(raw));
        assertThat(thrown).isExactlyInstanceOf(InvalidRequestException.class);
        return thrown.getMessage();
    }

    // ------------------------------------------------------------------ the syntax

    @Test
    void noQueryStringAndAnEmptyOneHaveNoParameters() {
        assertThat(parse(null).names()).isEmpty();
        assertThat(parse("").names()).isEmpty();
    }

    @Test
    void namesAndValuesAreSplitOnAmpersandAndOnTheFirstEquals() {
        QueryString query = parse("a=1&b=2&c=x=y");

        assertThat(query.names()).containsExactly("a", "b", "c");
        assertThat(query.values("a")).containsExactly("1");
        assertThat(query.values("c")).containsExactly("x=y");
    }

    @Test
    void aRepeatedNameKeepsEveryValueInOrder() {
        assertThat(parse("a=1&b=2&a=3").values("a")).containsExactly("1", "3");
    }

    @Test
    void aNameWithNoEqualsIsAnEmptyValue() {
        assertThat(parse("flag").values("flag")).containsExactly("");
    }

    @Test
    void aNameWithAnEmptyValueIsAnEmptyValue() {
        assertThat(parse("a=").values("a")).containsExactly("");
    }

    @Test
    void emptySegmentsAreIgnored() {
        assertThat(parse("&&a=1&").names()).containsExactly("a");
    }

    @Test
    void aParameterThatIsAbsentHasNoValues() {
        assertThat(parse("a=1").values("b")).isEqualTo(List.of());
    }

    // ------------------------------------------------------------------ decoding

    @Test
    void plusIsASpaceInNamesAndValues() {
        QueryString query = parse("a+b=x+y");

        assertThat(query.names()).containsExactly("a b");
        assertThat(query.values("a b")).containsExactly("x y");
    }

    @Test
    void anEncodedPlusIsAPlus() {
        assertThat(parse("a=%2B").values("a")).containsExactly("+");
        assertThat(parse("a=%2b").values("a")).containsExactly("+");
    }

    @Test
    void anEncodedSpaceIsASpace() {
        assertThat(parse("a=%20").values("a")).containsExactly(" ");
    }

    @Test
    void aMultiByteUtf8SequenceIsOneCharacter() {
        assertThat(parse("a=%C3%A9").values("a")).containsExactly("é");
        assertThat(parse("a=%E2%82%AC").values("a")).containsExactly("€");
        assertThat(parse("a=%F0%9F%98%80").values("a")).containsExactly("😀");
    }

    // Tomcat refuses raw non-ASCII in the request line itself; a container or a test that lets one through is read as text.
    @Test
    void aLiteralNonAsciiCharacterIsReadAsThatCharacter() {
        assertThat(parse("a=é").values("a")).containsExactly("é");
        assertThat(parse("a=€").values("a")).containsExactly("€");
        assertThat(parse("a=😀").values("a")).containsExactly("😀");
    }

    // ------------------------------------------------------------------ a query string that cannot be read: one fixed detail each

    @ParameterizedTest
    @ValueSource(strings = {
        "a=%zz", // not hex
        "a=%", // a lone percent
        "a=%2", // truncated
        "a=%2g", // the second digit is not hex
        "a=%E9", // a lone byte of a multi-byte sequence
        "a=%C3%28", // a lead byte followed by a non-continuation
        "a=%C0%AF", // an overlong encoding of "/"
        "a=%ED%A0%80", // a UTF-16 surrogate encoded as UTF-8
        "a=%F0%9F%98", // a four-byte sequence cut short
        "a=%FF", // a byte that is never valid
        "%zz=1", // the name is malformed, not the value
        "a=1&b=%zz", // a later segment
        "a=\ud800", // a literal surrogate with no partner: no UTF-8 form
        "a=\ude00",
    })
    void aBadPercentEncodingIsRefusedWithTheFixedDetail(String raw) {
        assertThat(refusalOf(raw)).isEqualTo(MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1", "=", "a=1&=2", "&=x", "=a=b"})
    void aParameterWithNoNameIsRefusedWithItsOwnFixedDetail(String raw) {
        assertThat(refusalOf(raw)).isEqualTo(NAMELESS);
    }

    @Test
    void theFirstProblemInTheStringDecides() {
        assertThat(refusalOf("=1&a=%zz")).isEqualTo(NAMELESS);
        assertThat(refusalOf("a=%zz&=1")).isEqualTo(MALFORMED);
    }

    @Test
    void aNamelessParameterIsReportedWithoutDecodingItsValue() {
        assertThat(refusalOf("=%zz")).isEqualTo(NAMELESS);
    }

    @Test
    void neitherDetailRepeatsWhatTheClientSent() {
        assertThat(refusalOf("secret_name=%zz")).doesNotContain("secret_name").doesNotContain("%zz");
        assertThat(refusalOf("=secret_value")).doesNotContain("secret_value");
    }
}
