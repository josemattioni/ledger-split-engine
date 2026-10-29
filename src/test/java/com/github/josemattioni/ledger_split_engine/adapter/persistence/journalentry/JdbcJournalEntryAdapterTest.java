package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryLine;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntry;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.MixedCurrencyException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.PostOutcome;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.UnknownAccountException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class JdbcJournalEntryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcJournalEntryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void postsValidMultiLineEntryAndReadsBackAssignedFields() {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();
        JournalEntry toPost = new JournalEntry(
                null,
                idempotencyKey,
                List.of(new EntryLine(1, sellerPayable, 9750L), new EntryLine(2, acquirerFee, -9750L)),
                null);

        PostOutcome outcome = adapter.insertOrFind(toPost);

        assertThat(outcome).isInstanceOf(PostOutcome.Created.class);
        JournalEntry posted = ((PostOutcome.Created) outcome).entry();

        assertThat(posted.entryId()).isNotNull();
        assertThat(posted.createdAt()).isNotNull();
        assertThat(posted.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(posted.lines())
                .containsExactly(new EntryLine(1, sellerPayable, 9750L), new EntryLine(2, acquirerFee, -9750L));
    }

    @Test
    void postingWithUnknownAccountCodeThrowsAndInsertsNothing() {
        String known = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String unknown = uniqueCode("does_not_exist");
        int journalEntriesBefore = countRows("journal_entries");
        int entryLinesBefore = countRows("entry_lines");
        JournalEntry toPost = new JournalEntry(
                null,
                uniqueIdempotencyKey(),
                List.of(new EntryLine(1, known, 100L), new EntryLine(2, unknown, -100L)),
                null);

        assertThatThrownBy(() -> adapter.insertOrFind(toPost))
                .isInstanceOf(UnknownAccountException.class)
                .hasMessageContaining(unknown);

        assertThat(countRows("journal_entries")).isEqualTo(journalEntriesBefore);
        assertThat(countRows("entry_lines")).isEqualTo(entryLinesBefore);
    }

    @Test
    void validateSingleCurrencyThrowsWhenAccountsSpanMultipleCurrencies() {
        List<JdbcJournalEntryAdapter.ResolvedAccount> resolved = List.of(
                new JdbcJournalEntryAdapter.ResolvedAccount("a", 1L, "BRL"),
                new JdbcJournalEntryAdapter.ResolvedAccount("b", 2L, "USD"));

        assertThatThrownBy(() -> JdbcJournalEntryAdapter.validateSingleCurrency(resolved))
                .isInstanceOf(MixedCurrencyException.class)
                .hasMessageContaining("BRL")
                .hasMessageContaining("USD");
    }

    @Test
    void validateSingleCurrencyAllowsAccountsSharingOneCurrency() {
        List<JdbcJournalEntryAdapter.ResolvedAccount> resolved = List.of(
                new JdbcJournalEntryAdapter.ResolvedAccount("a", 1L, "BRL"),
                new JdbcJournalEntryAdapter.ResolvedAccount("b", 2L, "BRL"));

        JdbcJournalEntryAdapter.validateSingleCurrency(resolved);
    }

    @Test
    void balanceOfAccountWithNoLinesIsZero() {
        String account = registerAccount("acquirer_fee", AccountType.EXPENSE);

        assertThat(adapter.balanceOf(account)).isZero();
    }

    @Test
    void balanceOfAccountReflectsSumOfMultiplePostedLines() {
        String sellerPayable = registerAccount("seller_payable", AccountType.LIABILITY);
        String acquirerFee = registerAccount("acquirer_fee", AccountType.EXPENSE);
        String commission = registerAccount("marketplace_commission", AccountType.REVENUE);

        adapter.insertOrFind(new JournalEntry(
                null,
                uniqueIdempotencyKey(),
                List.of(
                        new EntryLine(1, sellerPayable, 9750L),
                        new EntryLine(2, commission, -750L),
                        new EntryLine(3, acquirerFee, -9000L)),
                null));
        adapter.insertOrFind(new JournalEntry(
                null,
                uniqueIdempotencyKey(),
                List.of(new EntryLine(1, sellerPayable, -9750L), new EntryLine(2, acquirerFee, 9750L)),
                null));

        assertThat(adapter.balanceOf(sellerPayable)).isEqualTo(0L);
        assertThat(adapter.balanceOf(commission)).isEqualTo(-750L);
        assertThat(adapter.balanceOf(acquirerFee)).isEqualTo(750L);
    }
}
