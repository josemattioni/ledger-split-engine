package com.github.josemattioni.ledger_split_engine.config;

import com.github.josemattioni.ledger_split_engine.domain.journalentry.SettleWindow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start unless the database settings that make an as-of balance stable are in place: the application
 * role's {@code transaction_timeout} is greater than 0 and at most half the settle window, and prepared transactions
 * are disabled (see {@link SettleWindow}). The dependency is enforced in code, not assumed.
 *
 * <p>A {@link SmartInitializingSingleton} runs before the web server accepts requests (an {@code ApplicationRunner}
 * would let it come up first). It reads {@code pg_settings} through the application's own datasource, so it sees the
 * setting as the application's role gets it; unlike {@code SHOW}, a server older than PostgreSQL 17 just has no row
 * for {@code transaction_timeout}. Not loaded by {@code @WebMvcTest} slices (no datasource).
 */
@Component
public class TransactionBoundGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TransactionBoundGuard.class);

    private static final String TRANSACTION_TIMEOUT = "transaction_timeout";
    private static final String MAX_PREPARED_TRANSACTIONS = "max_prepared_transactions";

    private static final String SETTINGS_QUERY = "SELECT name, setting FROM pg_settings WHERE name IN ('"
            + TRANSACTION_TIMEOUT + "', '" + MAX_PREPARED_TRANSACTIONS + "')";

    /** What the database reports; an empty value means there is no such setting (or it could not be read). */
    public record Settings(OptionalLong transactionTimeoutMillis, OptionalLong maxPreparedTransactions) {}

    private final JdbcTemplate jdbcTemplate;

    public TransactionBoundGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        check();
    }

    /**
     * @throws IllegalStateException naming everything that is wrong with the database settings
     */
    public void check() {
        Settings settings = readSettings();
        List<String> violations =
                SettleWindow.violationsOf(settings.transactionTimeoutMillis(), settings.maxPreparedTransactions());
        if (violations.isEmpty()) {
            log.info(
                    "As-of balances are safe: transaction_timeout = {} ms, max_prepared_transactions = {}",
                    settings.transactionTimeoutMillis().getAsLong(),
                    settings.maxPreparedTransactions().getAsLong());
            return;
        }
        StringBuilder message = new StringBuilder("The database is not set up for as-of balances: ")
                .append(String.join("; ", violations))
                .append('.');
        if (settings.transactionTimeoutMillis().isPresent() && settings.transactionTimeoutMillis().getAsLong() == 0) {
            // Not the domain's business: the likely cause is how this project creates its database.
            message.append(" The usual cause is a database volume created before the init script gave the role its"
                    + " bound: recreate it with `docker compose down -v`.");
        }
        throw new IllegalStateException(message.toString());
    }

    /** Reads the two settings through this guard's datasource. */
    public Settings readSettings() {
        Map<String, Long> byName = new HashMap<>();
        jdbcTemplate.query(SETTINGS_QUERY, rs -> {
            byName.put(rs.getString("name"), Long.parseLong(rs.getString("setting")));
        });
        return new Settings(optional(byName, TRANSACTION_TIMEOUT), optional(byName, MAX_PREPARED_TRANSACTIONS));
    }

    private static OptionalLong optional(Map<String, Long> byName, String name) {
        Long value = byName.get(name);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
