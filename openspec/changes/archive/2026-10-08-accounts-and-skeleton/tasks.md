## 1. Project Infrastructure

- [x] 1.1 Add `docker-compose.yml` with a Postgres 16 service and verify `docker compose up` starts a Postgres instance reachable on the configured port
- [x] 1.2 Add Testcontainers dependencies (Postgres module, JUnit 5 integration) to `pom.xml` and verify `mvn test-compile` succeeds
- [x] 1.3 Configure `application.yaml` datasource settings to point at the docker-compose Postgres (no `spring.jpa.*` properties — there is no JPA/Hibernate dependency in this project and none is added; Flyway is the sole schema owner per design.md), and verify the configured connection details are correct by confirming the docker-compose Postgres is reachable with them (e.g. `pg_isready` or a plain `psql` connection) — a full `mvn spring-boot:run` startup check is deferred to 3.4, since the app has nothing to connect its datasource to meaningfully until the `accounts` table exists

## 2. Domain Model

- [x] 2.1 Implement `AccountType` enum (`ASSET`, `LIABILITY`, `REVENUE`, `EXPENSE`) and the `Currency` domain type (fixed to `BRL`) in the domain package, and verify the package has zero `org.springframework.*` / `jakarta.persistence.*` imports — no further test is needed for either beyond that, since both are single fixed-value/fixed-set types with no validation logic of their own
- [x] 2.2 Implement the `Account` domain record with a compact constructor that validates `code` against `^[a-z][a-z0-9_]*(:[a-z0-9_-]+)?$` (max 100 chars), rejecting anything else with a message describing the required shape, and verify with plain JUnit tests covering both valid codes (`marketplace_commission`, `seller_payable:123`) and invalid ones (uppercase, spaces, leading digit, over-length)
- [x] 2.3 In the same compact constructor, reject a non-null `owner_ref` that is blank or that has leading/trailing whitespace (i.e. not equal to its own trimmed form) with a message stating that `owner_ref` must be either omitted/null or non-blank with no leading/trailing whitespace, while continuing to allow `null` and any non-blank, untrimmed-equal string unvalidated in shape otherwise, and verify with plain JUnit tests covering `null` (accepted), `"123"` (accepted), `""` (rejected), `" "` (rejected), and `" 123 "` (rejected)
- [x] 2.4 Define `AccountAlreadyExistsException` (unchecked, domain package) and the `AccountPort` interface (`register`, `findByCode`) in the domain package, with `register` declared to throw `AccountAlreadyExistsException` on a duplicate `code` — defined here rather than in 3.3 (where the design decision was made) because `AccountPort`'s own signature needs the type to exist to compile; 3.3 has the JDBC adapter throw it, not define it

## 3. Persistence

- [x] 3.1 Write Flyway migration `V1__create_accounts.sql` creating the `accounts` table (`id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `code VARCHAR(100) UNIQUE NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]*(:[a-z0-9_-]+)?$')`, `owner_ref VARCHAR(100) NULL CHECK (owner_ref IS NULL OR (owner_ref = trim(owner_ref) AND length(owner_ref) > 0))`, `type VARCHAR(20) NOT NULL CHECK (type IN ('ASSET','LIABILITY','REVENUE','EXPENSE'))`, `currency VARCHAR(3) NOT NULL DEFAULT 'BRL' CHECK (currency IN ('BRL'))`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`), and verify Flyway reports the migration applied successfully against the docker-compose Postgres (`mvn flyway:info` or equivalent)
- [x] 3.2 Add a shared Testcontainers base test class (`@Testcontainers` + `@ServiceConnection`-wired `PostgreSQLContainer`, `@SpringBootTest` context so Flyway migrates it automatically) — pulled forward from 5.1, which needs the identical piece of infrastructure and is satisfied by this once reached. Implement a JDBC adapter (`JdbcTemplate`, no JPA) implementing `AccountPort`, and verify with a Testcontainers integration test extending the base class that an account can be inserted and fetched back by `code`
- [x] 3.3 Have the JDBC adapter catch the unique-`code` constraint violation and throw `AccountAlreadyExistsException` (defined in 2.4) instead of letting the raw SQL exception propagate, verified by a Testcontainers test that inserts a duplicate `code` and asserts this specific exception type
- [x] 3.4 Verify `mvn spring-boot:run` starts the application cleanly against the now-migrated schema (Flyway migration applies on startup with no errors) — the ordering check deferred from 1.3

## 4. Web Adapter

- [x] 4.1 Implement `POST /api/v1/accounts` accepting `code`, `type`, and optional `owner_ref` (no `currency` field in the request), and verify it returns 201 Created with a `Location: /api/v1/accounts/{code}` header on success
- [x] 4.2 Implement `GET /api/v1/accounts/{code}`, and verify it returns 200 with `code`, `type`, `owner_ref`, `currency`, and `created_at` (no internal `id`) for a registered account, and 404 for an unknown `code`
- [x] 4.3 Implement a generic `application/problem+json` error handler (e.g. `@ControllerAdvice`) that maps *any* request-binding failure (malformed JSON, invalid `type` value, missing required field) and *any* domain validation failure (invalid `code`, blank/padded `owner_ref`, over-length `code`, etc.) to 400 with a `detail` describing the violated rule — by exception type/category, not by enumerating each case — and maps `AccountAlreadyExistsException` to 409. Verify via MockMvc/integration tests covering the representative cases (malformed `code`, invalid `type`, blank `owner_ref`, duplicate `code`) plus at least one case *not* explicitly listed elsewhere (e.g. a missing required field, or an over-length `code`) to confirm the handler is generic rather than hardcoded per case

## 5. Integration Tests

- [x] 5.1 (Satisfied by 3.2, which needed this same base class earlier) — confirm the shared Testcontainers base test class still boots a real Postgres container with Flyway migrations applied and the full Spring context successfully
- [x] 5.2 Write end-to-end tests for: successful registration + lookup, duplicate `code` (409), malformed `code` (400), invalid `type` (400), empty-string and whitespace-padded `owner_ref` (`{"owner_ref": ""}` → 400, `{"owner_ref": " 123 "}` → 400), and lookup of an unknown `code` (404) — each test generating its own unique `code` and performing no cleanup (no `TRUNCATE`, no rollback-based isolation), and verify all pass against the real Postgres container

## 6. Definition of Done

- [x] 6.1 Write a README documenting `docker compose up`, `mvn spring-boot:run`, and curl commands that register an account and fetch it back by `code`
- [x] 6.2 Manually run the README's `docker compose up` → `mvn spring-boot:run` → curl sequence end-to-end and confirm it works exactly as documented — the running app responding correctly to curl is the deliverable, not just a green test suite
