package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.domain.account.AccountCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JournalEntryTest {

    private static final String IDEMPOTENCY_KEY = "key-1";

    @Test
    void acceptsBalancedMultiLineEntry() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", 9750L),
                new EntryLine(2, "marketplace_commission", -750L),
                new EntryLine(3, "acquirer_fee", -9000L));

        JournalEntry entry = new JournalEntry(null, IDEMPOTENCY_KEY, lines, null);

        assertThat(entry.lines()).containsExactlyElementsOf(lines);
    }

    @Test
    void rejectsUnbalancedEntry() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", 100L),
                new EntryLine(2, "marketplace_commission", -50L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(UnbalancedJournalEntryException.class)
                .hasMessageContaining("sum to 50");
    }

    // Precedence test (two defects on purpose: over the limit AND an overflowing sum). With the
    // per-line cap, lines large enough to overflow the sum are rejected by the cap first. The
    // Math.addExact guard remains as defense in depth but needs ~9 million max-size lines to reach,
    // so it has no dedicated unit test.
    @Test
    void precedence_longMaxLinesAreReportedAsOverTheLimitBeforeTheyAreSummed() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", Long.MAX_VALUE),
                new EntryLine(2, "marketplace_commission", Long.MAX_VALUE),
                new EntryLine(3, "acquirer_fee", 2L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(AmountLimitExceededException.class);
    }

    @Test
    void acceptsAmountsExactlyAtTheLimit() {
        long limit = JournalEntry.MAX_ABS_AMOUNT_CENTS;
        List<EntryLine> lines =
                List.of(new EntryLine(1, "seller_payable:1", limit), new EntryLine(2, "acquirer_fee", -limit));

        assertThat(new JournalEntry(null, IDEMPOTENCY_KEY, lines, null).lines()).hasSize(2);
    }

    @Test
    void rejectsAPositiveAmountOneOverTheLimitEvenWhenTheEntryBalances() {
        long over = JournalEntry.MAX_ABS_AMOUNT_CENTS + 1;
        List<EntryLine> lines =
                List.of(new EntryLine(1, "seller_payable:1", over), new EntryLine(2, "acquirer_fee", -over));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(AmountLimitExceededException.class)
                .hasMessageContaining("line 1")
                .hasMessageContaining(String.valueOf(JournalEntry.MAX_ABS_AMOUNT_CENTS));
    }

    @Test
    void rejectsANegativeAmountOneOverTheLimit() {
        long over = JournalEntry.MAX_ABS_AMOUNT_CENTS + 1;
        List<EntryLine> lines =
                List.of(new EntryLine(1, "seller_payable:1", -over), new EntryLine(2, "acquirer_fee", over));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(AmountLimitExceededException.class)
                .hasMessageContaining("line 1");
    }

    @Test
    void rejectsLongMinValueInsteadOfOverflowingAnAbsoluteValueCheck() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", Long.MIN_VALUE),
                new EntryLine(2, "acquirer_fee", Long.MAX_VALUE),
                new EntryLine(3, "marketplace_commission", 1L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(AmountLimitExceededException.class);
    }

    // Precedence test (two defects on purpose: over the limit AND unbalanced): the cap wins.
    @Test
    void precedence_anOverLimitLineIsReportedBeforeAnUnbalancedSum() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", 2 * JournalEntry.MAX_ABS_AMOUNT_CENTS),
                new EntryLine(2, "acquirer_fee", 5L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(AmountLimitExceededException.class);
    }

    // ------------------------------------------------------------------ degenerate entries (one defect each)

    @Test
    void acceptsAValidTwoLineEntry() {
        assertThat(new JournalEntry(null, IDEMPOTENCY_KEY, validTwoLines(), null).lines())
                .containsExactlyElementsOf(validTwoLines());
    }

    @Test
    void rejectsAnEntryWithNoLines() {
        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, List.of(), null))
                .isInstanceOf(DegenerateJournalEntryException.class)
                .hasMessageContaining("at least two lines (got 0)");
    }

    // Precedence test: a lone line is inherently two-defect (it is either zero or unbalanced), so it
    // can only ever pin which rule wins. The two-line minimum is checked first.
    @Test
    void precedence_aLoneNonZeroLineIsReportedAsTooFewLinesNotAsUnbalanced() {
        List<EntryLine> lines = List.of(new EntryLine(1, "seller_payable:1", 100L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(DegenerateJournalEntryException.class)
                .hasMessageContaining("at least two lines (got 1)");
    }

    // Precedence test: a lone zero line is both too few lines and a zero amount; the minimum wins.
    @Test
    void precedence_aLoneZeroLineIsReportedAsTooFewLinesNotAsAZeroAmount() {
        List<EntryLine> lines = List.of(new EntryLine(1, "seller_payable:1", 0L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(DegenerateJournalEntryException.class)
                .hasMessageContaining("at least two lines (got 1)");
    }

    // ------------------------------------------------------------------ line limit (one defect each)

    /**
     * {@code count} non-zero lines on distinct, well-formed accounts that sum to zero: every line but
     * the last is +1, the last is -(count - 1).
     */
    private static List<EntryLine> balancedLines(int count) {
        List<EntryLine> lines = new ArrayList<>();
        for (int lineNumber = 1; lineNumber < count; lineNumber++) {
            lines.add(new EntryLine(lineNumber, "acct_" + lineNumber, 1L));
        }
        lines.add(new EntryLine(count, "acct_" + count, -(count - 1L)));
        return lines;
    }

    @Test
    void acceptsExactlyTheMaximumNumberOfLines() {
        List<EntryLine> lines = balancedLines(JournalEntry.MAX_LINES);

        assertThat(new JournalEntry(null, IDEMPOTENCY_KEY, lines, null).lines()).hasSize(JournalEntry.MAX_LINES);
    }

    @Test
    void rejectsOneLineOverTheMaximumNamingTheCountAndTheLimit() {
        List<EntryLine> lines = balancedLines(JournalEntry.MAX_LINES + 1);

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(LineLimitExceededException.class)
                .hasMessage("journal entry has 101 lines; at most 100 are permitted");
    }

    // Precedence test (two defects on purpose: too many lines AND a zero amount). The count is checked
    // first because it is cheap and bounds the work of every check after it.
    @Test
    void precedence_aTooLongEntryIsReportedAsOverTheLimitBeforeItsLinesAreChecked() {
        List<EntryLine> lines = new ArrayList<>(balancedLines(JournalEntry.MAX_LINES + 1));
        lines.set(49, new EntryLine(50, "acct_50", 0L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(LineLimitExceededException.class);
    }

    // ------------------------------------------------------------------ account-code format (one defect each)

    // The code on line 2 is the only defect, so the message must name line 2, not just any line. Codes
    // are compared exactly: a padded or upper-cased spelling is rejected, never trimmed or folded.
    @ParameterizedTest
    @ValueSource(strings = {"ACQUIRER_FEE", " acquirer_fee", "acquirer_fee "})
    void rejectsAMalformedAccountCodeNamingTheLine(String malformed) {
        List<EntryLine> lines =
                List.of(new EntryLine(1, "seller_payable:1", 100L), new EntryLine(2, malformed, -100L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(InvalidAccountCodeException.class)
                .hasMessage("line 2: account_code \"" + malformed + "\" must be " + AccountCode.SHAPE);
    }

    // A code of 200,000 characters used to be repeated whole in the detail: a 200 KB response for a 200 KB request.
    @Test
    void aHugeMalformedAccountCodeIsNamedByItsFirstSixtyFourCharactersOnly() {
        String huge = "A".repeat(200_000);
        List<EntryLine> lines =
                List.of(new EntryLine(1, "seller_payable:1", 100L), new EntryLine(2, huge, -100L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(InvalidAccountCodeException.class)
                .hasMessage("line 2: account_code \"" + "A".repeat(64) + "…\" must be " + AccountCode.SHAPE);
    }

    // A malformed key (here 1,000 characters, so the only defect is its length) is cut the same way.
    @Test
    void aHugeMalformedIdempotencyKeyIsNamedByItsFirstSixtyFourCharactersOnly() {
        String huge = "k".repeat(1_000);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, huge, validTwoLines(), null))
                .withMessage("idempotency_key must be 1-255 visible ASCII characters (no whitespace): \""
                        + "k".repeat(64) + "…\"");
    }

    // Precedence test (two defects on purpose: a malformed code on line 1 AND a zero amount on line 2).
    // What each line names is checked before what it carries.
    @Test
    void precedence_aMalformedCodeIsReportedBeforeAZeroAmount() {
        List<EntryLine> lines =
                List.of(new EntryLine(1, "SELLER_PAYABLE:1", 100L), new EntryLine(2, "acquirer_fee", 0L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(InvalidAccountCodeException.class)
                .hasMessageContaining("line 1");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void rejectsAZeroAmountLineInAnyPosition(int zeroLine) {
        // The other two lines cancel, so the zero amount is the only defect.
        long[] nonZeroAmounts = {100L, -100L};
        String[] accounts = {"seller_payable:1", "marketplace_commission", "acquirer_fee"};
        List<EntryLine> lines = new ArrayList<>();
        int next = 0;
        for (int lineNumber = 1; lineNumber <= 3; lineNumber++) {
            long amount = lineNumber == zeroLine ? 0L : nonZeroAmounts[next++];
            lines.add(new EntryLine(lineNumber, accounts[lineNumber - 1], amount));
        }

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(DegenerateJournalEntryException.class)
                .hasMessageContaining("line " + zeroLine + " has a zero amount_cents");
    }

    @Test
    void rejectsARepeatedAccountOnAdjacentLines() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", 100L),
                new EntryLine(2, "seller_payable:1", 50L),
                new EntryLine(3, "acquirer_fee", -150L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(DegenerateJournalEntryException.class)
                .hasMessageContaining("account_code seller_payable:1 appears on more than one line (lines 1 and 2)");
    }

    @Test
    void rejectsARepeatedAccountOnNonAdjacentLines() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", 100L),
                new EntryLine(2, "acquirer_fee", 50L),
                new EntryLine(3, "seller_payable:1", -150L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(DegenerateJournalEntryException.class)
                .hasMessageContaining("account_code seller_payable:1 appears on more than one line (lines 1 and 3)");
    }

    @Test
    void rejectsADebitAndCreditOnTheSameAccountThatCancelOut() {
        List<EntryLine> lines =
                List.of(new EntryLine(1, "seller_payable:1", 700L), new EntryLine(2, "seller_payable:1", -700L));

        assertThatThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .isInstanceOf(DegenerateJournalEntryException.class)
                .hasMessageContaining("account_code seller_payable:1 appears on more than one line (lines 1 and 2)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "a b", "tab\ttab"})
    void rejectsMalformedIdempotencyKey(String key) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, key, validTwoLines(), null))
                .withMessageContaining("idempotency_key must be 1-255 visible ASCII");
    }

    @Test
    void rejectsOverLengthIdempotencyKey() {
        String tooLong = "a".repeat(256);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, tooLong, validTwoLines(), null))
                .withMessageContaining("idempotency_key must be 1-255 visible ASCII");
    }

    @Test
    void rejectsNullIdempotencyKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, null, validTwoLines(), null))
                .withMessageContaining("idempotency_key must not be null");
    }

    @Test
    void rejectsNullLines() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, null, null))
                .withMessageContaining("lines must not be null");
    }

    @Test
    void rejectsLinesContainingNullElement() {
        List<EntryLine> lines = Arrays.asList(new EntryLine(1, "acquirer_fee", 100L), null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .withMessageContaining("must not contain a null element");
    }

    @Test
    void rejectsOutOfOrderLineNumbers() {
        List<EntryLine> lines = List.of(
                new EntryLine(2, "seller_payable:1", 100L),
                new EntryLine(1, "marketplace_commission", -100L));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .withMessageContaining("line_number values must be exactly 1..2 in order");
    }

    @Test
    void rejectsDuplicateLineNumbers() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", 100L),
                new EntryLine(1, "marketplace_commission", -100L));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .withMessageContaining("line_number values must be exactly 1..2 in order");
    }

    @Test
    void rejectsSkippedLineNumber() {
        List<EntryLine> lines = List.of(
                new EntryLine(1, "seller_payable:1", 100L),
                new EntryLine(3, "marketplace_commission", -100L));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry(null, IDEMPOTENCY_KEY, lines, null))
                .withMessageContaining("line_number values must be exactly 1..2 in order");
    }

    // sameRequestAs: every "false" case differs from the base request in exactly one way (a different
    // line count necessarily changes more than one line, so the two count cases pin that instead).

    @Test
    void sameRequestAsIsTrueForIdenticalLines() {
        assertThat(entryOf(baseLines()).sameRequestAs(entryOf(baseLines()))).isTrue();
    }

    @Test
    void sameRequestAsIgnoresServerAssignedFields() {
        JournalEntry stored =
                new JournalEntry(UUID.randomUUID(), IDEMPOTENCY_KEY, baseLines(), Instant.parse("2026-10-08T12:00:00Z"));
        JournalEntry request = entryOf(baseLines());

        assertThat(stored.sameRequestAs(request)).isTrue();
        assertThat(request.sameRequestAs(stored)).isTrue();
    }

    @Test
    void sameRequestAsIsFalseForADifferentAmountThatStillBalances() {
        List<EntryLine> differentAmount =
                List.of(new EntryLine(1, "seller_payable:1", 150L), new EntryLine(2, "marketplace_commission", -150L));

        assertThat(entryOf(baseLines()).sameRequestAs(entryOf(differentAmount))).isFalse();
    }

    @Test
    void sameRequestAsIsFalseForADifferentAccount() {
        List<EntryLine> differentAccount =
                List.of(new EntryLine(1, "seller_payable:1", 100L), new EntryLine(2, "acquirer_fee", -100L));

        assertThat(entryOf(baseLines()).sameRequestAs(entryOf(differentAccount))).isFalse();
    }

    @Test
    void sameRequestAsIsFalseForTheSameLinesReordered() {
        List<EntryLine> reordered =
                List.of(new EntryLine(1, "marketplace_commission", -100L), new EntryLine(2, "seller_payable:1", 100L));

        assertThat(entryOf(baseLines()).sameRequestAs(entryOf(reordered))).isFalse();
    }

    @Test
    void sameRequestAsIsFalseWhenTheOtherEntryHasAnExtraLine() {
        List<EntryLine> extraLine = List.of(
                new EntryLine(1, "seller_payable:1", 100L),
                new EntryLine(2, "marketplace_commission", -60L),
                new EntryLine(3, "acquirer_fee", -40L));

        assertThat(entryOf(baseLines()).sameRequestAs(entryOf(extraLine))).isFalse();
    }

    @Test
    void sameRequestAsIsFalseWhenTheOtherEntryIsMissingALine() {
        List<EntryLine> threeLines = List.of(
                new EntryLine(1, "seller_payable:1", 100L),
                new EntryLine(2, "marketplace_commission", -60L),
                new EntryLine(3, "acquirer_fee", -40L));

        assertThat(entryOf(threeLines).sameRequestAs(entryOf(baseLines()))).isFalse();
    }

    // ------------------------------------------------------------------ the entry being reversed is part of the request

    private static final UUID ORIGINAL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ANOTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static JournalEntry reversing(UUID reversesEntryId) {
        return new JournalEntry(null, IDEMPOTENCY_KEY, baseLines(), null, reversesEntryId);
    }

    @Test
    void anEntryBuiltWithoutALinkReversesNothing() {
        JournalEntry ordinary = entryOf(baseLines());

        assertThat(ordinary.reversesEntryId()).isNull();
        assertThat(ordinary.isReversal()).isFalse();
    }

    @Test
    void anEntryWithALinkIsAReversal() {
        JournalEntry reversal = reversing(ORIGINAL);

        assertThat(reversal.reversesEntryId()).isEqualTo(ORIGINAL);
        assertThat(reversal.isReversal()).isTrue();
    }

    @Test
    void sameRequestAsIsTrueForTheSameLinesAndTheSameEntryBeingReversed() {
        assertThat(reversing(ORIGINAL).sameRequestAs(reversing(ORIGINAL))).isTrue();
    }

    @Test
    void sameRequestAsIsFalseForTheSameLinesReversingADifferentEntry() {
        assertThat(reversing(ORIGINAL).sameRequestAs(reversing(ANOTHER))).isFalse();
    }

    // Identical lines, so only the link tells them apart: an ordinary entry that happens to carry a
    // reversal's lines is not the same request as the reversal, in either direction.
    @Test
    void sameRequestAsIsFalseWhenOnlyOneOfTheTwoIsAReversal() {
        JournalEntry ordinary = entryOf(baseLines());

        assertThat(ordinary.sameRequestAs(reversing(ORIGINAL))).isFalse();
        assertThat(reversing(ORIGINAL).sameRequestAs(ordinary)).isFalse();
    }

    // ------------------------------------------------------------------ reversalOf

    private static final Instant ORIGINAL_CREATED_AT = Instant.parse("2026-10-15T12:00:00Z");

    /** A stored ordinary entry, as the port would load it. */
    private static JournalEntry stored(List<EntryLine> lines) {
        return new JournalEntry(ORIGINAL, "original-key", lines, ORIGINAL_CREATED_AT);
    }

    @Test
    void reversalOfNegatesEveryLineKeepingItsLineNumberAndAccount() {
        JournalEntry reversal = JournalEntry.reversalOf(stored(baseLines()), "reversal-key");

        assertThat(reversal.lines())
                .containsExactly(
                        new EntryLine(1, "seller_payable:1", -100L), new EntryLine(2, "marketplace_commission", 100L));
    }

    @Test
    void reversalOfNegatesAThreeLineEntryInOrder() {
        List<EntryLine> original = List.of(
                new EntryLine(1, "seller_payable:1", 9750L),
                new EntryLine(2, "marketplace_commission", -750L),
                new EntryLine(3, "acquirer_fee", -9000L));

        JournalEntry reversal = JournalEntry.reversalOf(stored(original), "reversal-key");

        assertThat(reversal.lines())
                .containsExactly(
                        new EntryLine(1, "seller_payable:1", -9750L),
                        new EntryLine(2, "marketplace_commission", 750L),
                        new EntryLine(3, "acquirer_fee", 9000L));
    }

    @Test
    void reversalOfLinksToTheOriginalCarriesTheGivenKeyAndLeavesServerFieldsUnassigned() {
        JournalEntry reversal = JournalEntry.reversalOf(stored(baseLines()), "reversal-key");

        assertThat(reversal.reversesEntryId()).isEqualTo(ORIGINAL);
        assertThat(reversal.isReversal()).isTrue();
        assertThat(reversal.idempotencyKey()).isEqualTo("reversal-key");
        assertThat(reversal.entryId()).as("assigned by the store").isNull();
        assertThat(reversal.createdAt()).as("assigned by the store").isNull();
    }

    @Test
    void reversalOfAnEntryAtTheLimitsReversesAllHundredLinesAtPlusAndMinusTheCap() {
        long cap = JournalEntry.MAX_ABS_AMOUNT_CENTS;
        List<EntryLine> original = new ArrayList<>();
        for (int i = 1; i <= JournalEntry.MAX_LINES; i++) {
            original.add(new EntryLine(i, "acct_" + i, i % 2 == 1 ? cap : -cap));
        }

        JournalEntry reversal = JournalEntry.reversalOf(stored(original), "reversal-key");

        assertThat(reversal.lines()).hasSize(JournalEntry.MAX_LINES);
        assertThat(reversal.lines().get(0).amountCents()).isEqualTo(-cap);
        assertThat(reversal.lines().get(1).amountCents()).isEqualTo(cap);
    }

    @Test
    void reversalOfAReversalIsRefusedNamingTheEntryAndSayingHowToUndoIt() {
        JournalEntry storedReversal = new JournalEntry(ANOTHER, "r-key", baseLines(), ORIGINAL_CREATED_AT, ORIGINAL);

        assertThatThrownBy(() -> JournalEntry.reversalOf(storedReversal, "reversal-key"))
                .isInstanceOf(CannotReverseAReversalException.class)
                .hasMessageContaining(ANOTHER.toString())
                .hasMessageContaining("post the original movement again as an ordinary entry");
    }

    // A reversal is only ever built from a loaded entry, so a missing id is a defect (a 500), not a
    // client error: it must be an IllegalArgumentException, not one of the rule exceptions.
    @Test
    void reversalOfAnEntryWithNoEntryIdIsAnIllegalArgument() {
        JournalEntry unsaved = entryOf(baseLines());

        assertThatIllegalArgumentException().isThrownBy(() -> JournalEntry.reversalOf(unsaved, "reversal-key"));
    }

    @Test
    void reversalOfRejectsAMalformedKeyAsAnIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> JournalEntry.reversalOf(stored(baseLines()), "bad key"))
                .withMessageContaining("idempotency_key must be 1-255");
    }

    // The web layer maps IllegalArgumentException to 400 only around request-to-domain construction;
    // these are 422 / 404 outcomes and must never be mistaken for a malformed request.
    @Test
    void theReversalExceptionsAreNotIllegalArgumentExceptions() {
        assertThat(new CannotReverseAReversalException(ORIGINAL)).isNotInstanceOf(IllegalArgumentException.class);
        assertThat(new EntryAlreadyReversedException(ORIGINAL)).isNotInstanceOf(IllegalArgumentException.class);
        assertThat(new JournalEntryNotFoundException(ORIGINAL)).isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theReversalExceptionsNameTheEntry() {
        assertThat(new EntryAlreadyReversedException(ORIGINAL).getMessage())
                .isEqualTo("entry 11111111-1111-1111-1111-111111111111 has already been reversed");
        assertThat(new JournalEntryNotFoundException(ORIGINAL).getMessage())
                .isEqualTo("No journal entry with entry_id \"11111111-1111-1111-1111-111111111111\"");
    }

    private static JournalEntry entryOf(List<EntryLine> lines) {
        return new JournalEntry(null, IDEMPOTENCY_KEY, lines, null);
    }

    private static List<EntryLine> baseLines() {
        return List.of(new EntryLine(1, "seller_payable:1", 100L), new EntryLine(2, "marketplace_commission", -100L));
    }

    private static List<EntryLine> validTwoLines() {
        return List.of(new EntryLine(1, "acquirer_fee", 100L), new EntryLine(2, "seller_payable:1", -100L));
    }
}
