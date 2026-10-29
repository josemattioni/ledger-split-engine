package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

/**
 * {@code service-health}: the only operational endpoint is {@code /actuator/health}, it answers with the status and
 * nothing else, and the rest of Actuator is not reachable. Over a real socket, because the endpoint is outside the
 * MVC slice the controller tests use and its answer is the framework's own.
 *
 * <p>The database-down side of the contract is {@link HealthEndpointDatabaseDownTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theServiceAnswersUpWithTheStatusAndNothingElseAndAsksForNoCredentials() throws Exception {
        // No Authorization header and no cookie are sent: the client above adds none.
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("the health document, not a problem document")
                .doesNotContain("problem");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator",
        "/actuator/env",
        "/actuator/configprops",
        "/actuator/beans",
        "/actuator/mappings",
        "/actuator/loggers",
        "/actuator/metrics",
        "/actuator/heapdump",
        "/actuator/threaddump",
        "/actuator/info",
    })
    void everyOperationalEndpointOtherThanHealthIsNotServed(String path) throws Exception {
        assertThat(get(path).statusCode()).as(path).isEqualTo(404);
    }

    @Test
    void thePoolGivesUpOnAConnectionAfterThreeSeconds() throws Exception {
        // A database that cannot be reached must be answered as DOWN within a probe's patience: the health check waits
        // on the pool, and the pool's default wait is 30 seconds.
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(3000L);
    }

    @Test
    void theDatabaseIsCheckedByTheBoundedIndicatorAndNotByBootsOwn() {
        // Boot's own check hangs on a frozen database: the pool hands it a recently used connection without
        // validating it, and its query waits for ever. With both on, the bounded one runs first and takes that
        // connection, so HealthEndpointDatabaseDownTest cannot tell (found by mutation: it stayed green with Boot's
        // check back on). The beans are checked directly instead.
        assertThat(context.containsBean("databaseHealthIndicator")).isTrue();
        assertThat(context.containsBean("dbHealthContributor")).isFalse();
    }

    @Test
    void thePoolChecksAnIdleConnectionForAtMostTwoSeconds() throws Exception {
        // Measured with the database paused: the answer took 5.0 s with the default 5 s and 4.0 s with 2 s, because
        // the pool checks an idle connection before handing it out, then the next one, and only gives up once the
        // connection timeout has run out: the answer takes about the connection timeout plus one validation.
        assertThat(dataSource.unwrap(HikariDataSource.class).getValidationTimeout()).isEqualTo(2000L);
    }
}
