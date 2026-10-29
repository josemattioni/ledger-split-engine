package com.github.josemattioni.ledger_split_engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The application starts, startup guard included. It runs against the same Testcontainers database as every other
 * integration test (created from the docker-compose init script, so with the {@code ledger_app} role and its
 * transaction bound), so a fresh clone needs only Docker running, not {@code docker compose up}.
 */
@SpringBootTest
class LedgerSplitEngineApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
