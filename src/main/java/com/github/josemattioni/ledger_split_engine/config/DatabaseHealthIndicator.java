package com.github.josemattioni.ledger_split_engine.config;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The database part of {@code /actuator/health}, in place of Boot's own ({@code management.health.db.enabled} is off).
 * Boot's check runs a query on a pooled connection, and a database that is frozen rather than refusing connections
 * never answers it: pgjdbc has no read timeout by default and the pool's {@code connection-timeout} only bounds
 * <em>getting</em> a connection, so the request hung. This check asks the connection whether it is valid, with a
 * timeout that pgjdbc applies as a network timeout, so a database that does not answer is DOWN after
 * {@link #VALIDITY_TIMEOUT_SECONDS} seconds. It says only UP or DOWN; the reason is logged, never returned.
 */
@Component
class DatabaseHealthIndicator implements HealthIndicator {

    /** How long a pooled connection has to answer before the database counts as down. */
    static final int VALIDITY_TIMEOUT_SECONDS = 2;

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    private final DataSource dataSource;

    DatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(VALIDITY_TIMEOUT_SECONDS)) {
                return Health.up().build();
            }
            log.warn("The database health check failed: the connection is not valid");
        } catch (SQLException | RuntimeException e) {
            log.warn("The database health check failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
        return Health.down().build();
    }
}
