package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * {@code service-health}, the other half: with the database unreachable the endpoint answers {@code 503} with
 * {@code DOWN}, quickly, and when the database is reachable again it answers {@code UP} without a restart.
 *
 * <p>The application has a database of its own here, not the container the other tests share: the database is
 * <em>paused</em> (it stops answering and keeps its port, so the pool can find it again), which would disturb every
 * other test. It does not extend {@code AbstractIntegrationTest} for the same reason: that class points the
 * datasource at the shared container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointDatabaseDownTest {

    private static final String INIT_SCRIPT = "01-create-ledger-app-role.sql";

    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/initdb/" + INIT_SCRIPT),
                    "/docker-entrypoint-initdb.d/" + INIT_SCRIPT);

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "ledger_app");
        registry.add("spring.datasource.password", () -> "ledger_app");
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        // Shorter than the application's own bound, so the test does not wait for the most the service would.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> health() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aDatabaseThatStopsAnsweringIsDownQuicklyAndUpAgainWhenItReturnsWithoutARestart() throws Exception {
        assertThat(health().statusCode()).as("up before the database is paused").isEqualTo(200);

        DockerClient docker = DATABASE.getDockerClient();
        docker.pauseContainerCmd(DATABASE.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            HttpResponse<String> down = health();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(down.statusCode()).isEqualTo(503);
            assertThat(down.body()).isEqualTo("{\"status\":\"DOWN\"}");
            assertThat(took).as("the time to a DOWN answer").isLessThan(Duration.ofSeconds(8));
        } finally {
            docker.unpauseContainerCmd(DATABASE.getContainerId()).exec();
        }

        // The pool finds the database again by itself; the endpoint is polled for a few seconds, not once.
        HttpResponse<String> back = health();
        for (int attempt = 0; attempt < 40 && back.statusCode() != 200; attempt++) {
            Thread.sleep(500);
            back = health();
        }
        assertThat(back.statusCode()).as("up again after the database returned").isEqualTo(200);
        assertThat(back.body()).isEqualTo("{\"status\":\"UP\"}");
    }
}
