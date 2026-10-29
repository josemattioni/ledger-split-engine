package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises {@code trg_entry_lines_balance} directly via {@link JdbcTemplate}, bypassing the
 * domain layer's own balance check entirely, so these tests reach the deferred trigger itself
 * rather than being rejected before any row is ever inserted.
 *
 * <p>None of these tests use {@code @Transactional}-rollback-style test isolation. A deferred
 * constraint trigger only evaluates at a real {@code COMMIT} (or an explicit
 * {@code SET CONSTRAINTS ... IMMEDIATE}); Spring's test-rollback mechanism never issues a real
 * commit, so the trigger would never run at all under it. Each test below drives its own
 * transaction manually via {@link TransactionTemplate}, so the commit (or the explicit immediate
 * check) genuinely happens.
 */
@SpringBootTest
class EntryLinesBalanceTriggerTest extends AbstractIntegrationTest {

    private static final String LDG01 = "LDG01";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
    }

    private long registerAccountId(String prefix, AccountType type) {
        String code = registerAccount(prefix, type);
        return jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE code = ?", Long.class, code);
    }

    private long insertHeader(String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO journal_entries (idempotency_key) VALUES (?) RETURNING id", Long.class, idempotencyKey);
    }

    private void insertLine(long journalEntryId, int lineNumber, long accountId, long amountCents) {
        jdbcTemplate.update(
                "INSERT INTO entry_lines (journal_entry_id, line_number, account_id, amount_cents) VALUES (?, ?, ?, ?)",
                journalEntryId, lineNumber, accountId, amountCents);
    }

    private int entryLineCount(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM entry_lines
                WHERE journal_entry_id = (SELECT id FROM journal_entries WHERE idempotency_key = ?)
                """,
                Integer.class,
                idempotencyKey);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ 4.1a / 4.1b / 4.1c: default (deferred) checking

    @Test
    void balancedLinesCommitSucceeds_positiveControl() {
        long account1 = registerAccountId("seller_payable", AccountType.LIABILITY);
        long account2 = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();

        txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(idempotencyKey);
            insertLine(headerId, 1, account1, 100L);
            insertLine(headerId, 2, account2, -100L);
        });

        assertThat(entryLineCount(idempotencyKey)).isEqualTo(2);
    }

    @Test
    void unbalancedLinesCommitFailsWithLdg01() {
        long account1 = registerAccountId("seller_payable", AccountType.LIABILITY);
        long account2 = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(idempotencyKey);
            insertLine(headerId, 1, account1, 100L);
            insertLine(headerId, 2, account2, -50L);
        }));

        assertThat(thrown)
                .as("commit of an unbalanced entry must fail")
                .isNotNull()
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(sqlStateOf(thrown)).as("SQLSTATE reported by Postgres").isEqualTo(LDG01);
    }

    @Test
    void overflowingSumCommitFailsWithLdg01NotNumericOutOfRange() {
        long account1 = registerAccountId("seller_payable", AccountType.LIABILITY);
        long account2 = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        long account3 = registerAccountId("marketplace_commission", AccountType.REVENUE);
        String idempotencyKey = uniqueIdempotencyKey();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(idempotencyKey);
            insertLine(headerId, 1, account1, Long.MAX_VALUE);
            insertLine(headerId, 2, account2, Long.MAX_VALUE);
            insertLine(headerId, 3, account3, 2L);
        }));

        assertThat(thrown).as("commit of an overflowing-sum entry must fail").isNotNull();
        assertThat(sqlStateOf(thrown))
                .as("must report the domain's own LDG01, not Postgres's generic 22003 numeric overflow")
                .isEqualTo(LDG01);
    }

    // ------------------------------------------------------------------ 4.2a / 4.2b: forced immediate checking

    @Test
    void immediateCheckingAfterUnbalancedLinesFailsBeforeCommit() {
        long account1 = registerAccountId("seller_payable", AccountType.LIABILITY);
        long account2 = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
                    long headerId = insertHeader(idempotencyKey);
                    insertLine(headerId, 1, account1, 100L);
                    insertLine(headerId, 2, account2, -50L);
                    jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
                }))
                .isNotInstanceOf(org.springframework.transaction.TransactionSystemException.class)
                .satisfies(ex -> assertThat(sqlStateOf(ex)).isEqualTo(LDG01));
    }

    @Test
    void immediateCheckingAfterBalancedLinesSucceeds_positiveControl() {
        long account1 = registerAccountId("seller_payable", AccountType.LIABILITY);
        long account2 = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        String idempotencyKey = uniqueIdempotencyKey();

        txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(idempotencyKey);
            insertLine(headerId, 1, account1, 100L);
            insertLine(headerId, 2, account2, -100L);
            jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });

        assertThat(entryLineCount(idempotencyKey)).isEqualTo(2);
    }
}
