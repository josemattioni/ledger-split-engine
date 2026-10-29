package com.github.josemattioni.ledger_split_engine.adapter.persistence.account;

import com.github.josemattioni.ledger_split_engine.domain.account.Account;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountAlreadyExistsException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountPort;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.github.josemattioni.ledger_split_engine.domain.account.Currency;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountAdapter implements AccountPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAccountAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts only the columns a client supplies. {@code currency} is not one of them: it, {@code id} and
     * {@code created_at} come from the column defaults, because {@code ledger_app} holds no insert privilege on
     * them (V5). The stored currency is read back with {@code RETURNING}. While {@code Currency} has one value
     * the default and the account agree; {@code AccountCurrencyRoundTripTest} fails the moment a second value
     * exists that this statement would silently store as the default.
     */
    @Override
    public Account register(Account account) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    INSERT INTO accounts (code, owner_ref, type)
                    VALUES (?, ?, ?)
                    RETURNING code, owner_ref, type, currency, created_at
                    """,
                    this::mapRow,
                    account.code(), account.ownerRef(), account.type().name());
        } catch (DuplicateKeyException e) {
            throw new AccountAlreadyExistsException(account.code());
        }
    }

    @Override
    public Optional<Account> findByCode(String code) {
        return jdbcTemplate
                .query(
                        "SELECT code, owner_ref, type, currency, created_at FROM accounts WHERE code = ?",
                        this::mapRow,
                        code)
                .stream()
                .findFirst();
    }

    private Account mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getString("code"),
                rs.getString("owner_ref"),
                AccountType.valueOf(rs.getString("type")),
                Currency.valueOf(rs.getString("currency")),
                rs.getTimestamp("created_at").toInstant());
    }
}
