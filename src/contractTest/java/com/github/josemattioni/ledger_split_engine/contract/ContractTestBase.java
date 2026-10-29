package com.github.josemattioni.ledger_split_engine.contract;

import io.restassured.RestAssured;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for black-box contract tests: the full app on a random port, backed by a real
 * Postgres container, exercised over HTTP with REST-Assured. Run by failsafe
 * ({@code mvn verify}), never by surefire ({@code mvn test}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ContractTestBase {

    /**
     * Singleton container: started once per JVM and shared by every contract test class (Ryuk
     * removes it at exit). A per-class {@code @Container} would be stopped after the first class
     * while Spring's cached context kept pointing at its dead port.
     */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/initdb/01-create-ledger-app-role.sql"),
                    "/docker-entrypoint-initdb.d/01-create-ledger-app-role.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // The app connects as ledger_app (created by the init script); Flyway migrates as the
        // container's own user, which owns the tables.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "ledger_app");
        registry.add("spring.datasource.password", () -> "ledger_app");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
    }

    /** A fresh account code matching {@code ^[a-z][a-z0-9_]*(:[a-z0-9_-]+)?$}. */
    protected String uniqueCode() {
        return "contract_test:" + UUID.randomUUID().toString().replace("-", "");
    }
}
