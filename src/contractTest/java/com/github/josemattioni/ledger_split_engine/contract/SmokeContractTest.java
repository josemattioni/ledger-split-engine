package com.github.josemattioni.ledger_split_engine.contract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.lessThan;

import org.junit.jupiter.api.Test;

/** Proves the contract-test wiring (server, port, base path, database) works end to end. */
class SmokeContractTest extends ContractTestBase {

    @Test
    void appRespondsWithoutServerError() {
        given()
                .when()
                .get("/accounts/{code}", uniqueCode())
                .then()
                .statusCode(lessThan(500));
    }
}
