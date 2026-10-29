## Context

- **Build:** Spring Boot 4.1.1 parent, Java 21, `spring-boot-maven-plugin` (the repackaged jar is `target/ledger_split_engine-0.0.1-SNAPSHOT.jar`), the Maven wrapper in `only-script` mode (it downloads Maven on first use), and `mvnw` staged as `100755` in the index (F1). `./mvnw verify` runs both suites in about two minutes on this machine and needs Docker (Testcontainers; the contract suite starts its own container and application).
- **Configuration:** `application.yaml` carries the development datasource URL (`localhost:5433`) and both credentials. Spring Boot reads any property from an environment variable (`SPRING_DATASOURCE_URL`, `SPRING_FLYWAY_PASSWORD`, …), so nothing in the file has to change for the container to override it. The `ledger_app` password, however, is created by `docker/initdb/01-create-ledger-app-role.sql`, a plain SQL file with the same development value; changing it is a change to that script, not to the environment.
- **Web layer:** the query-parameter interceptor is registered for `/api/v1/**` only, so `/actuator/**` is outside it; the body-size filter wraps every request, health included. `PROJECT-CONVENTIONS.md` says all endpoints live under `/api/v1` and every error is problem+json.
- **Nothing exists yet:** no `.github/`, no `Dockerfile`, no `.dockerignore`, no actuator reference, and the repository has no git remote (the local branch is `master`).

## Goals / Non-Goals

**Goals:**
- A pushed commit is built and tested by a machine that is not this one.
- A client or orchestrator can ask whether the service can serve requests, and a down database is answered quickly as down.
- The application runs as a container started by compose, configured from the environment, after Postgres is ready.
- The new endpoint is specified and tested, and the convention it departs from says so.

**Non-Goals:**
- OpenAPI, metrics, tracing or any actuator endpoint other than health; a separate management port; authentication.
- Publishing the image to a registry; the CI badge (needs the remote's URL); pinning the actions by commit hash.
- Making the `ledger_app` password configurable from the environment (it is set by the init script; see Risks).
- Any change under `/api/v1`.

## Decisions

### 1. Health through Actuator, at its default path, outside `/api/v1`
`spring-boot-starter-actuator` with `management.endpoints.web.exposure.include=health`, `management.endpoint.health.show-details=never`, `management.endpoint.health.probes.enabled=false` (the liveness and readiness groups are on by default and add a `groups` member to the answer, which the exact-body test caught), `management.endpoints.web.discovery.enabled=false` (without it `/actuator` still lists the exposed endpoints), and `management.health.diskspace.enabled=false` (a nearly full laptop disk is not the service being down). The contributors that remain are the database and the always-up ping. The answer is `{"status":"UP"}` with `200`, or `{"status":"DOWN"}` with `503`.

*Alternatives considered.* (a) A hand-written `GET /api/v1/health`: it would keep the "everything under `/api/v1`, everything problem+json" convention intact and let the interceptor and the exception handler apply, but orchestrators and tooling expect `/actuator/health`, and the database check would be reinvented. (b) Actuator with `management.endpoints.web.base-path=/api/v1/actuator`: under the convention's prefix, but then the query-parameter interceptor's `/api/v1/**` pattern would cover handlers it was not written for. (c) A separate management port: the usual way to keep operational endpoints off the public port, and cheap to add later; with no authentication and a status-only answer it buys little now.

*The convention.* `PROJECT-CONVENTIONS.md` gets a carve-out: operational endpoints are not part of the API. `/actuator/health` is the only one, it lives outside `/api/v1`, its answers are the framework's health document (not problem+json), and the API-wide rules (error format, unknown query parameters) are the API's.

### 2. A down database is answered in seconds: a short pool wait and a bounded check
The check must be bounded in both places it can wait.

- **Getting a connection.** Hikari waits up to `connection-timeout` (30 seconds by default) before failing, longer than any probe waits. `spring.datasource.hikari.connection-timeout` is lowered to 3000 ms, and `validation-timeout` to 2000 ms (the pool checks an idle connection before handing it out, and its default 5 s check would otherwise dominate). A request that cannot get a pooled connection then also fails after 3 seconds instead of 30 (a `500`, as before), which under overload is the better failure.
- **Using it (found at apply).** With the database paused, which is what a frozen host or a network partition looks like, Boot's own database check hung instead of answering `DOWN`: the request thread sat in a pgjdbc socket read (`PGStream.receiveChar`, seen with `jstack`) on a connection it already held, and pgjdbc has no read timeout by default, so the pool's timeout never applies. Measured: the request started with the database paused finished with `200` only after 8.45 s, when it was unpaused, and `HealthEndpointDatabaseDownTest` hit its own 20 s limit with the pool's timeout at 2 s. So Boot's `db` contributor is turned off (`management.health.db.enabled: false`) and replaced by a small `HealthIndicator` that borrows a connection and calls `connection.isValid(2)`; pgjdbc bounds `isValid` with a network timeout, so a database that does not answer is `DOWN` after about two seconds.

*Alternatives considered for the second half.* (a) Keep Boot's check and accept that a frozen database hangs it: the spec's "a few seconds" scenario would have to go, and an orchestrator would see an unreachable service instead of a down one. (b) pgjdbc `socketTimeout` on the datasource: it bounds every read, so it would have to exceed the 30-second transaction bound or it would cut legitimate long reads, which defeats the purpose. (c) `Statement.setQueryTimeout`: pgjdbc enforces it by sending a cancel request over a *new* connection to the same server, the one that is not answering. (d) A separate datasource for health: more configuration for the same effect. The user chose the replacement indicator at apply.

*Side effects to review.* A saturated pool makes the check wait up to 3 seconds and then report `DOWN`, which Boot's indicator did too. The check reports nothing but the status: no exception text reaches the answer (it is logged at `WARN` with the exception's class and message, without a stack trace).

### 3. A `Dockerfile`, not buildpacks
Multi-stage: a build stage on a JDK 21 image runs `./mvnw -B -Dmaven.test.skip=true package` (the tests are CI's job, and the Testcontainers suites cannot run inside an image build), and the run stage copies the jar onto a JRE 21 image, as a non-root user, port 8080, with a `HEALTHCHECK` on `/actuator/health`. The base image is the Alpine JRE if BusyBox `wget` is there for the check, which apply confirms by building it.

*Alternatives considered.* `spring-boot:build-image` (Cloud Native Buildpacks): no file to maintain, but it needs the Docker daemon, pulls a large builder, hides what is in the image, and cannot be the `build:` of a compose service. Jib: another plugin for the same result. Layered-jar extraction: faster rebuilds, more lines, not needed for a portfolio image.

### 4. The application is an opt-in compose service
`app` is under the `app` profile, so `docker compose up -d` still starts Postgres only and the documented quickstart (`./mvnw spring-boot:run`) is unchanged; `docker compose --profile app up -d --build` starts both. It takes the datasource URL (`jdbc:postgresql://postgres:5432/…`) and both credential pairs from `SPRING_*` variables written as `${VARIABLE:-development value}`, so the development defaults keep working and a real value comes from the shell or an `.env` file. It starts when Postgres is `service_healthy` and has the image's health check. The Postgres service's own credentials get the same treatment.

### 5. CI: two jobs, tags not hashes
`.github/workflows/ci.yml` runs on `push` to `main` and `master` (GitHub's default is `main`, the local branch is `master`) and on `pull_request`, with `permissions: contents: read` and a concurrency group that cancels a superseded run. Job `verify`: `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 21, `cache: maven`), `./mvnw -B verify`, and on failure `actions/upload-artifact@v4` of the Surefire and Failsafe reports. Job `image` (in parallel): `docker compose --profile app up -d --build --wait`, then `curl -f` of the health endpoint, the container log on failure, and `down -v`. The workflow cannot be run from here: its steps are checked locally (the same commands, and `actionlint` if it can be run), and the first push is the real test. Actions are pinned by major tag, as the finding suggests; commit hashes are the stricter choice and are left out.

### 6. Tests
- **`HealthEndpointTest`** (`@SpringBootTest` on a random port, real HTTP, the shared container): `200` and exactly `{"status":"UP"}`, nothing else in the body, no credentials sent; `404` for `/actuator`, `/actuator/env`, `/actuator/configprops`, `/actuator/beans`, `/actuator/mappings`, `/actuator/loggers`, `/actuator/metrics`, `/actuator/heapdump`, `/actuator/threaddump` and `/actuator/info`.
- **`DatabaseHealthIndicatorTest`** (plain Mockito, no Spring): a connection that is valid is `UP` with no details; one that is not valid, one whose `isValid` throws, and a `getConnection` that throws are each `DOWN` with no details; `isValid` is called with `2` and the connection is always closed.
- **`HealthEndpointDatabaseDownTest`**: a context of its own, on a container of its own, so it never disturbs the shared one. The container is paused (the database stops answering without its port changing), the endpoint must answer `503` with `{"status":"DOWN"}` within a few seconds (the test lowers the pool's timeout further), and after the container is unpaused it answers `UP` again without a restart. If pausing proves unreliable on some Docker, the fallback is revoking and restoring `LOGIN` on the application role in the same container.
- **Mutation checks**: removing `show-details: never` or the `include` line, turning the discovery switch back on, turning `probes.enabled` back on, calling `isValid(0)` (unbounded), and leaving Boot's `db` contributor on, each turn a test red.

## Risks / Trade-offs

- **[The endpoint is new attack surface]** → Only `health`, status only, discovery off, and a test that lists what must stay `404`.
- **[`DOWN` arrives late, or never]** → Decision 2: a short pool wait and a bounded check. Figures, measured with the real application and the database paused: before, the request hung until the database came back (8.45 s, when it was unpaused); after, with the pool timeouts and the bounded check: a frozen database (paused) is `DOWN` in 4.0 s (three requests: 4.03, 4.01, 4.03 s), a stopped one (connection refused) in 3.0 s, and either is `UP` again on the first request after it returns (4 ms). The 4.0 s is the pool at work, not the check: it validates an idle connection (2 s), then the next one (2 s), and gives up once the 3 s connection timeout has run out, so the answer takes about the connection timeout plus one validation. With Hikari's default 5 s validation it was 5.0 s, which is why `validation-timeout` is set to 2000 as well (a lower value would shave another second at the price of a stricter liveness check on every borrow). The health requests leave one `WARN` line each in the log, with the exception's class and message and no stack.
- **[A lower pool timeout changes behavior under overload]** → A `500` after 3 seconds instead of 30; flagged for confirmation below.
- **[CI is unproven until it runs on GitHub]** → The steps are run locally, the YAML is linted if `actionlint` can run, and the task list says the first push is the real check.
- **[The `ledger_app` password cannot be set from the environment]** It lives in the init script. → Said plainly in the docs: the application's connection settings come from the environment; provisioning the role with another password is the init script's (or, in a real deployment, the operator's) job.
- **[Development credentials appear as defaults in `docker-compose.yml`]** → They already did, and the README already says they are for local development; the `Dockerfile` itself contains none.
- **[Alpine uses musl]** → The application has no native dependencies; the build is tested on the image.
- **[A first image build downloads Maven and the dependencies]** → Expected; a BuildKit cache mount keeps rebuilds quick.

## Decisions to confirm at review

1. `/actuator/health` at the default path, outside `/api/v1`, with a documented carve-out (Decision 1), not `/api/v1/health`.
2. `connection-timeout` lowered to 3 seconds, which also affects a saturated pool, and Boot's database check replaced by a bounded one (Decision 2; the replacement was chosen by the user at apply, after the hang was shown).
3. A `Dockerfile` rather than buildpacks (Decision 3).
4. The compose service behind a profile, so the default `up` is unchanged (Decision 4).
5. A second CI job that builds the image and checks the endpoint (Decision 5).
6. Disk space off and discovery off in the health configuration (Decision 1).

## Migration Plan

Nothing to migrate: no schema or data change, and nothing under `/api/v1` changes. The dependency and the `management.*` lines take effect at the next start. Order of work: the tests first (red), the dependency and configuration (green), the pool timeout and its measurement, the `Dockerfile` and compose service built and run locally, the workflow, the docs, the full `verify`.
