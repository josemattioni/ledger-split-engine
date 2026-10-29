package com.github.josemattioni.ledger_split_engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The database health check never waits on a database that does not answer: it asks the connection whether it is valid
 * with a timeout of two seconds (pgjdbc applies it as a network timeout), and it says only UP or DOWN, with no detail.
 * Boot's own check hung on a frozen database because its query ran on a connection with no read timeout; the real
 * behavior is {@code HealthEndpointDatabaseDownTest}, these are the cases around it.
 */
class DatabaseHealthIndicatorTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseHealthIndicator indicator = new DatabaseHealthIndicator(dataSource);

    @Test
    void aConnectionThatIsValidIsUpWithNoDetails() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
        verify(connection).close();
    }

    @Test
    void aConnectionThatIsNotValidIsDownWithNoDetails() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
        verify(connection).close();
    }

    @Test
    void aValidityCheckThatFailsIsDownWithNoDetailsAndTheConnectionIsStillClosed() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenThrow(new SQLException("connection to localhost:5433 lost"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).as("no error text reaches the answer").isEmpty();
        verify(connection).close();
    }

    @Test
    void aPoolThatCannotGiveAConnectionIsDownWithNoDetails() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("request timed out after 3000ms"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void theValidityCheckIsBoundedToTwoSeconds() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        indicator.health();

        verify(connection).isValid(2);
        verify(connection, never()).isValid(0);
    }
}
