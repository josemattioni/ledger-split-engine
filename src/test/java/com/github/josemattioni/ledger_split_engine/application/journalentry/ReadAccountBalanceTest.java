package com.github.josemattioni.ledger_split_engine.application.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.AsOfNotSettledException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The order and the window of an as-of read, with a fake port whose clock the test controls. No Spring, no database. */
class ReadAccountBalanceTest {

    private static final String CODE = "seller_payable:1";
    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.123456Z");

    private final FakeJournalEntryPort journalEntryPort = new FakeJournalEntryPort();
    private final FakeAccountPort accountPort = new FakeAccountPort();
    private final ReadAccountBalance readAccountBalance = new ReadAccountBalance(accountPort, journalEntryPort);

    @BeforeEach
    void setUp() {
        accountPort.accounts.put(CODE, new Account(CODE, null, AccountType.LIABILITY, Currency.BRL, Instant.EPOCH));
        journalEntryPort.databaseNow = NOW;
        journalEntryPort.currentBalance = 9750;
        journalEntryPort.asOfBalance = 4000;
    }

    // ------------------------------------------------------------------ no as_of: the current balance

    @Test
    void withoutAsOfItReturnsTheCurrentBalanceWithANullAsOf() {
        AccountBalanceResult result = readAccountBalance.execute(CODE, null);

        assertThat(result).isEqualTo(new AccountBalanceResult(CODE, Currency.BRL, 9750, null));
    }

    @Test
    void withoutAsOfItNeverReadsTheClockNorTheAsOfSum() {
        readAccountBalance.execute(CODE, null);

        assertThat(journalEntryPort.clockReads).isZero();
        assertThat(journalEntryPort.asOfRequests).isEmpty();
        assertThat(journalEntryPort.currentBalanceCalls).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the settle window

    @Test
    void anInstantSixtyOneSecondsOldIsAnsweredWithTheSumForThatInstant() {
        Instant asOf = NOW.minusSeconds(61);

        AccountBalanceResult result = readAccountBalance.execute(CODE, asOf);

        assertThat(result).isEqualTo(new AccountBalanceResult(CODE, Currency.BRL, 4000, asOf));
        assertThat(journalEntryPort.asOfRequests).containsExactly(asOf);
        assertThat(journalEntryPort.currentBalanceCalls).as("the current balance is not read for an as-of request").isZero();
    }

    @Test
    void anInstantExactlyOneWindowOldIsAnswered() {
        Instant asOf = NOW.minus(Duration.ofSeconds(60));

        assertThat(readAccountBalance.execute(CODE, asOf).balanceCents()).isEqualTo(4000);
    }

    @Test
    void anInstantFiftyNineSecondsOldIsRefused() {
        assertThatThrownBy(() -> readAccountBalance.execute(CODE, NOW.minusSeconds(59)))
                .isInstanceOf(AsOfNotSettledException.class)
                .hasMessageContaining("60 seconds");
        assertThat(journalEntryPort.asOfRequests).as("the sum is never read for a refused instant").isEmpty();
    }

    @Test
    void anInstantAMicrosecondInsideTheWindowIsRefused() {
        Instant asOf = NOW.minus(Duration.ofSeconds(60)).plus(Duration.ofNanos(1_000));

        assertThatThrownBy(() -> readAccountBalance.execute(CODE, asOf)).isInstanceOf(AsOfNotSettledException.class);
    }

    @Test
    void aFutureInstantIsRefused() {
        assertThatThrownBy(() -> readAccountBalance.execute(CODE, NOW.plusSeconds(3600)))
                .isInstanceOf(AsOfNotSettledException.class);
    }

    @Test
    void theWindowIsMeasuredFromTheDatabaseClockNotFromTheInstantItself() {
        journalEntryPort.databaseNow = NOW.plus(Duration.ofHours(1));

        // 61 s before the application's idea of "now" is an hour and 61 s before the database's: settled.
        assertThat(readAccountBalance.execute(CODE, NOW.minusSeconds(61)).balanceCents()).isEqualTo(4000);
        // The same instant is not settled once the database clock is behind it.
        journalEntryPort.databaseNow = NOW.minusSeconds(3600);
        assertThatThrownBy(() -> readAccountBalance.execute(CODE, NOW.minusSeconds(61)))
                .isInstanceOf(AsOfNotSettledException.class);
    }

    // ------------------------------------------------------------------ the order: the account first

    @Test
    void anUnknownAccountIsNotFoundBeforeTheClockOrTheSumIsTouched() {
        assertThatThrownBy(() -> readAccountBalance.execute("no_such_account", NOW.minusSeconds(120)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("No account with code \"no_such_account\"");

        assertThat(journalEntryPort.clockReads).isZero();
        assertThat(journalEntryPort.asOfRequests).isEmpty();
        assertThat(journalEntryPort.currentBalanceCalls).isZero();
    }

    @Test
    void anUnknownAccountWithAnUnsettledInstantIsStillNotFoundNotUnsettled() {
        assertThatThrownBy(() -> readAccountBalance.execute("no_such_account", NOW.plusSeconds(1)))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(journalEntryPort.clockReads).isZero();
    }

    /** A hand-written in-memory account store with the port's contract. */
    private static final class FakeAccountPort implements AccountPort {

        final Map<String, Account> accounts = new HashMap<>();

        @Override
        public Account register(Account account) {
            accounts.put(account.code(), account);
            return account;
        }

        @Override
        public Optional<Account> findByCode(String code) {
            return Optional.ofNullable(accounts.get(code));
        }
    }
}
