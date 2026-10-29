package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SettleWindowTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123456Z");
    private static final OptionalLong BOUND_OK = OptionalLong.of(30_000);
    private static final OptionalLong NO_PREPARED = OptionalLong.of(0);

    // ------------------------------------------------------------------ the constants

    @Test
    void theWindowIsSixtySecondsAndTheLargestBoundIsHalfOfIt() {
        assertThat(SettleWindow.WINDOW).isEqualTo(Duration.ofSeconds(60));
        assertThat(SettleWindow.MAX_TRANSACTION_BOUND).isEqualTo(Duration.ofSeconds(30));
    }

    // ------------------------------------------------------------------ isSettled

    @Test
    void anInstantOlderThanTheWindowIsSettled() {
        assertThat(SettleWindow.isSettled(NOW.minusSeconds(61), NOW)).isTrue();
    }

    @Test
    void anInstantExactlyOneWindowOldIsSettled() {
        assertThat(SettleWindow.isSettled(NOW.minus(SettleWindow.WINDOW), NOW)).isTrue();
    }

    @Test
    void anInstantOneMicrosecondInsideTheWindowIsNotSettled() {
        Instant justInside = NOW.minus(SettleWindow.WINDOW).plus(Duration.ofNanos(1_000));
        assertThat(SettleWindow.isSettled(justInside, NOW)).isFalse();
    }

    @Test
    void anInstantFiftyNineSecondsOldIsNotSettled() {
        assertThat(SettleWindow.isSettled(NOW.minusSeconds(59), NOW)).isFalse();
    }

    @Test
    void theCurrentInstantIsNotSettled() {
        assertThat(SettleWindow.isSettled(NOW, NOW)).isFalse();
    }

    @Test
    void aFutureInstantIsNotSettled() {
        assertThat(SettleWindow.isSettled(NOW.plusSeconds(1), NOW)).isFalse();
    }

    // ------------------------------------------------------------------ violationsOf: the transaction bound

    @Test
    void aBoundOfExactlyThirtySecondsWithPreparedTransactionsOffIsAcceptable() {
        assertThat(SettleWindow.violationsOf(BOUND_OK, NO_PREPARED)).isEmpty();
    }

    @Test
    void aShorterBoundIsAcceptable() {
        assertThat(SettleWindow.violationsOf(OptionalLong.of(1), NO_PREPARED)).isEmpty();
    }

    @Test
    void anAbsentBoundIsRefusedWithAMessageNamingPostgres17() {
        List<String> violations = SettleWindow.violationsOf(OptionalLong.empty(), NO_PREPARED);

        assertThat(violations).singleElement().asString().contains("transaction_timeout").contains("PostgreSQL 17");
    }

    @Test
    void aBoundOfZeroIsRefusedAsDisabled() {
        List<String> violations = SettleWindow.violationsOf(OptionalLong.of(0), NO_PREPARED);

        assertThat(violations).singleElement().asString().contains("transaction_timeout").contains("disabled");
    }

    @Test
    void aBoundOneMillisecondOverThirtySecondsIsRefusedStatingTheMaximum() {
        List<String> violations = SettleWindow.violationsOf(OptionalLong.of(30_001), NO_PREPARED);

        assertThat(violations)
                .singleElement()
                .asString()
                .contains("30001 ms")
                .contains("at most 30 seconds");
    }

    @Test
    void theThreeBoundProblemsHaveDifferentMessages() {
        String absent = SettleWindow.violationsOf(OptionalLong.empty(), NO_PREPARED).get(0);
        String zero = SettleWindow.violationsOf(OptionalLong.of(0), NO_PREPARED).get(0);
        String tooLong = SettleWindow.violationsOf(OptionalLong.of(30_001), NO_PREPARED).get(0);

        assertThat(List.of(absent, zero, tooLong)).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ violationsOf: prepared transactions

    @Test
    void preparedTransactionsEnabledAreRefusedWithAMessageNamingTheSetting() {
        List<String> violations = SettleWindow.violationsOf(BOUND_OK, OptionalLong.of(1));

        assertThat(violations).singleElement().asString().contains("max_prepared_transactions is 1").contains("must be 0");
    }

    @Test
    void anUnreadablePreparedTransactionsSettingIsRefusedToo() {
        List<String> violations = SettleWindow.violationsOf(BOUND_OK, OptionalLong.empty());

        assertThat(violations).singleElement().asString().contains("max_prepared_transactions could not be read");
    }

    @Test
    void preparedTransactionsAreRefusedWhateverTheBound() {
        assertThat(SettleWindow.violationsOf(OptionalLong.of(1), OptionalLong.of(5))).hasSize(1);
        assertThat(SettleWindow.violationsOf(OptionalLong.of(30_000), OptionalLong.of(100))).hasSize(1);
    }

    // ------------------------------------------------------------------ several problems at once

    @Test
    void everyProblemIsReportedAtOnce() {
        List<String> violations = SettleWindow.violationsOf(OptionalLong.of(0), OptionalLong.of(5));

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("transaction_timeout");
        assertThat(violations.get(1)).contains("max_prepared_transactions");
    }
}
