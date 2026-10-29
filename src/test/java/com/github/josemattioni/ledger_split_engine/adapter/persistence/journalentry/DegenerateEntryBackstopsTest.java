package com.github.josemattioni.ledger_split_engine.adapter.persistence.journalentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database backstops behind the domain's degenerate-entry guards: {@code entry_lines} CHECK
 * ({@code amount_cents <> 0}), UNIQUE ({@code journal_entry_id, account_id}) and the deferred
 * {@code LDG03} trigger on {@code journal_entries} (at least two lines). The domain rejects these
 * shapes first, so none is reachable through the API: these tests insert raw rows as
 * {@code ledger_app} through {@link JdbcTemplate}, bypassing the domain on purpose.
 *
 * <p>Like the other deferred-trigger tests, none use {@code @Transactional} rollback isolation:
 * the deferred checks only run at a real {@code COMMIT}, which {@link TransactionTemplate} drives.
 */
@SpringBootTest
class DegenerateEntryBackstopsTest extends AbstractIntegrationTest {

    private static final String LDG01 = "LDG01";
    private static final String LDG03 = "LDG03";
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

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

    private int headerCount(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, idempotencyKey);
        return count == null ? 0 : count;
    }

    private int lineCount(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM entry_lines
                WHERE journal_entry_id = (SELECT id FROM journal_entries WHERE idempotency_key = ?)
                """,
                Integer.class,
                idempotencyKey);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ LDG03: at least two lines

    @Test
    void positiveControl_aBalancedTwoLineEntryOnDistinctAccountsCommits() {
        long account1 = registerAccountId("seller_payable", AccountType.LIABILITY);
        long account2 = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        String key = uniqueIdempotencyKey();

        txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(key);
            insertLine(headerId, 1, account1, 100L);
            insertLine(headerId, 2, account2, -100L);
        });

        assertThat(lineCount(key)).isEqualTo(2);
    }

    @Test
    void aHeaderWithZeroLinesFailsAtCommitWithLdg03() {
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> txTemplate.executeWithoutResult(status -> insertHeader(key)));

        assertThat(thrown)
                .as("commit of a header with no lines must fail")
                .isInstanceOf(TransactionSystemException.class);
        assertThat(sqlStateOf(thrown)).isEqualTo(LDG03);
        assertThat(headerCount(key)).as("nothing committed").isZero();
    }

    @Test
    void aOneLineEntryCannotCommit() {
        long account = registerAccountId("seller_payable", AccountType.LIABILITY);
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(key);
            insertLine(headerId, 1, account, 100L);
        }));

        // A lone non-zero line is both under the two-line minimum (LDG03) and unbalanced (LDG01), so
        // either trigger may report it. Deferred events fire in the order they were queued and the
        // header's comes first, so Postgres actually raises LDG03 (observed against Postgres 16);
        // asserting membership pins the guarantee (it cannot commit), not the queue order.
        assertThat(thrown).as("commit of a one-line entry must fail").isNotNull();
        assertThat(sqlStateOf(thrown)).isIn(LDG01, LDG03);
        assertThat(headerCount(key)).as("nothing committed").isZero();
    }

    // ------------------------------------------------------------------ CHECK (amount_cents <> 0)

    @Test
    void aZeroAmountLineAloneFailsAtInsertWithTheCheckConstraint() {
        long account = registerAccountId("seller_payable", AccountType.LIABILITY);
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(key);
            insertLine(headerId, 1, account, 0L);
        }));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(CHECK_VIOLATION);
        assertThat(constraintOf(thrown)).isEqualTo("entry_lines_amount_nonzero_ck");
        assertThat(headerCount(key)).as("nothing committed").isZero();
    }

    @Test
    void aZeroAmountLineAmongOtherwiseValidLinesFailsAtInsertWithTheCheckConstraint() {
        long account1 = registerAccountId("seller_payable", AccountType.LIABILITY);
        long account2 = registerAccountId("acquirer_fee", AccountType.EXPENSE);
        long account3 = registerAccountId("marketplace_commission", AccountType.REVENUE);
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(key);
            insertLine(headerId, 1, account1, 250L);
            insertLine(headerId, 2, account2, 0L);
            insertLine(headerId, 3, account3, -250L);
        }));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(CHECK_VIOLATION);
        assertThat(constraintOf(thrown)).isEqualTo("entry_lines_amount_nonzero_ck");
        assertThat(headerCount(key)).as("nothing committed").isZero();
    }

    // ------------------------------------------------------------------ UNIQUE (journal_entry_id, account_id)

    @Test
    void theSameAccountTwiceInOneEntryFailsAtInsertWithItsOwnUniqueConstraint() {
        long account = registerAccountId("seller_payable", AccountType.LIABILITY);
        String key = uniqueIdempotencyKey();

        Throwable thrown = catchThrowable(() -> txTemplate.executeWithoutResult(status -> {
            long headerId = insertHeader(key);
            insertLine(headerId, 1, account, 700L);
            insertLine(headerId, 2, account, -700L);
        }));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown)).isEqualTo(UNIQUE_VIOLATION);
        assertThat(constraintOf(thrown))
                .as("names the entry_lines constraint, not the idempotency key's: a repeated key is a replay or"
                        + " a reuse, never a unique violation")
                .isEqualTo("entry_lines_entry_account_uq")
                .isNotEqualTo("journal_entries_idempotency_key_uq");
        assertThat(headerCount(key)).as("nothing committed").isZero();
    }
}
