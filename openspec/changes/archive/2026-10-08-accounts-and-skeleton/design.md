## Context

Greenfield Spring Boot 4.1.1 project (Java 21) with `spring-boot-starter-webmvc`, `-validation`, `-flyway`, and the Postgres driver already in `pom.xml`, but no application code beyond the default skeleton. This is slice 1 of a vertical-slice build; see proposal.md for motivation. The decisions here fix the architectural pattern (hexagonal, JDBC over JPA, Flyway-owned schema) and the schema shape that every later slice (journal entries, splits, settlement) inherits, so several choices below optimize for what's expensive to change once `entry_lines` exists rather than for slice-1 convenience alone.

Two separate Postgres instances are involved and should not be conflated: `docker-compose.yml` provisions a Postgres 16 instance for local development, against which `mvn spring-boot:run` and the README's curl-based definition of done run; Testcontainers provisions its own, separate Postgres instance per test run for the integration test suite. Both apply the same Flyway migrations, but they are not the same running database.

## Goals / Non-Goals

**Goals:**
- Establish the hexagonal package boundary and JDBC-based persistence pattern this project will reuse for journal entries, splits, and settlement in later slices.
- Get the `accounts` schema right on the dimensions that are expensive to change after `entry_lines` exists and starts holding foreign keys into it (surrogate key type, natural key shape, validation layering).

**Non-Goals:**
- Journal entry / entry line schema, balance derivation, posting, reversal, idempotency keys, settlement scheduling — later slices, per proposal.md.
- Multi-currency support. Currency is fixed to `BRL`; the column exists so a future currency doesn't require a schema migration, but no multi-currency logic is built now.
- Authentication/authorization on the API — not addressed in this slice.
- Network exposure beyond localhost. This slice assumes local/developer-machine access only (`docker compose up` + `mvn spring-boot:run` on localhost); reachability from outside the local machine, TLS, and production network topology are unaddressed and stated here explicitly so this stays a known gap rather than an unnoticed assumption — revisit when a slice first needs this deployed anywhere but a developer machine.

## Decisions

### Surrogate key: `BIGINT GENERATED ALWAYS AS IDENTITY`, not `UUID`
`accounts.id` is a `BIGINT GENERATED ALWAYS AS IDENTITY` primary key, never exposed in API responses or URLs. `code` is the externally visible natural key.

Rationale: `entry_lines`, added in a later slice, will be the append-heavy table and will FK to `accounts.id`. A `BIGINT` FK is 8 bytes vs. 16 for `UUID`, and sequential bigint inserts don't fragment the B-tree index the way random UUIDs do on a high-growth table. The usual UUID argument — sequential ids leak volume through the API — doesn't apply here, because `id` never leaves the database; `code` is what appears in URLs and logs.

Alternative considered: UUID PK. Rejected for the reasons above; the "don't expose sequential ids to clients" benefit UUID normally buys is already achieved by using `code` as the API identifier instead.

### Natural key: `code`, shape-validated but not meaning-validated
`code` is `VARCHAR(100) UNIQUE NOT NULL` with `CHECK (code ~ '^[a-z][a-z0-9_]*(:[a-z0-9_-]+)?$')`. The prefix vocabulary (`seller_payable`, `acquirer_fee`, `marketplace_commission`, ...) is never encoded as an enum, lookup table, or fixed list — only the shape is enforced.

Rationale: the set of account purposes will grow as new fee types and clearing accounts are introduced; encoding the vocabulary would force a code change (and migration) each time. Constraining only the shape keeps the vocabulary open while still preventing format drift (`SellerPayable123` vs. `seller_payable:123`).

Precisely what `UNIQUE (code)` guarantees, and what it doesn't: it enforces that the exact string `code` is not reused — it prevents two rows both named `seller_payable:123`. It does **not** enforce that a given seller has at most one payable account: nothing stops `seller_payable:123` and `payable_seller:123` from both existing, or `seller_payable:123` and `seller_payable:0123`. "One payable account per seller" is therefore a caller convention (callers construct `code` consistently from `owner_ref`), not a database-enforced guarantee. If that needs to become a real guarantee later, the mechanism is `UNIQUE (owner_ref, type)`, added when it's actually needed — it isn't added now because it isn't needed yet and `owner_ref` is nullable (see below).

### No `owner_type` column
`owner_ref` is a nullable opaque string with no accompanying `owner_type`. The `code` prefix already implies ownership kind (e.g. `seller_payable:123` implies a seller); a separate `owner_type` column would be a second source of the same fact, free to drift from the first.

### `owner_ref`: deliberately opaque, no format enforced — but not blank or padded
`owner_ref` is `VARCHAR(100) NULL` with `CHECK (owner_ref IS NULL OR (owner_ref = trim(owner_ref) AND length(owner_ref) > 0))`. This is a deliberate decision, not an oversight: this slice has no Party/Seller aggregate and no integration with a real owning system, so there is no actual identifier format to validate against yet. Inventing a shape now (numeric, UUID, a specific prefix convention, etc.) risks guessing wrong and having to migrate the column or its constraint once a real owning system exists.

A concrete shape for `owner_ref` **must be agreed before any real owning system starts writing values into it** — at that point this decision should be revisited and, likely, a format constraint added.

Opaque format doesn't have to mean opaque emptiness, though: without the `CHECK`, `NULL` and `''` would both mean "no owner," as two different representations of the same fact, and a `WHERE owner_ref IS NULL` query would silently miss the empty-string rows. Format stays opaque, but null-or-non-blank is enforced at both layers — `Account`'s compact constructor and the `CHECK` — and `null` is the only way to express "no owner." A value with leading or trailing whitespace (`" 123 "`) is rejected rather than trimmed on write, for the same reason `""` is rejected rather than nulled: trimming on write would make `" 123 "` and `"123"` two distinct stored values that are indistinguishable in any log or UI, silently missing rows under `WHERE owner_ref = '123'` — the same one-fact-two-representations problem the blank check exists to prevent.

`Account`'s compact constructor also rejects `owner_ref` over 100 characters, matching the column's `VARCHAR(100)` limit. This was a real gap, found by QA testing: the domain never checked length, so an over-length value sailed through to the JDBC insert and hit Postgres's column-length enforcement there instead — an unhandled `DataAccessException`, surfaced as a bare 500, not a controlled 400. No new `CHECK` was needed for the backstop layer here — `VARCHAR(100)` already *is* the database-level length enforcement, the same role a `CHECK` plays for the other constraints on this column; the bug was purely a missing domain-layer gate in front of a backstop that was already there.

### `type` and `currency`: `VARCHAR` + `CHECK`, not native Postgres `ENUM`
`type` is `VARCHAR(20) NOT NULL CHECK (type IN ('ASSET','LIABILITY','REVENUE','EXPENSE'))`; `currency` is `VARCHAR(3) NOT NULL DEFAULT 'BRL' CHECK (currency IN ('BRL'))`. Neither uses a native Postgres `ENUM` type.

Rationale, in priority order:
1. **Consistency across the table.** `code`, `type`, and `currency` are all closed-ish columns on one table — an open-vocabulary shape-constrained string, a fixed accounting classification, and a today-single-value currency code. Enforcing all three the same way (domain type + `CHECK`) means a reader never has to ask why one column is treated differently, even though `currency` is arguably the most closed of the three today.
2. **Flyway/transaction friction.** `ALTER TYPE ... ADD VALUE` cannot run inside a transaction that has already referenced the type, and Flyway wraps each migration in a transaction by default. Adding a value to a native enum later would mean `flyway.executeInTransaction=false` and hand-managed failure handling on that one migration. A `CHECK` constraint is ordinary DDL and migrates like everything else.

Native enum's usual advantages (4-byte storage vs. string, DB-level type safety) don't matter at the row counts an `accounts` table has, and `CHECK` gives equivalent rejection.

### Two-layer validation: domain first, `CHECK` as backstop
`AccountType` is a Java enum. `code` is validated in the compact constructor of the domain type that wraps it, using a regex kept semantically identical to the `CHECK` constraint's — Java's `String.matches()` is implicitly fully anchored, while Postgres `~` is not, so the SQL pattern needs the explicit `^...$` shown in the schema.

Rationale: a `CHECK` violation surfaces as Postgres error class `23514`, naming the constraint rather than the offending value — not something to let bubble up as a generic 500. The domain layer is the primary gate and produces the 400 problem+json response with a message describing the rule; the database constraint exists only to catch anything written outside the domain (a backstop, not the primary contract).

### Duplicate registration signaled by a domain exception, not `Optional`/boolean
`AccountPort.register(Account)` throws `AccountAlreadyExistsException` (unchecked, domain package) when `code` already exists, rather than returning `Optional<Account>` (empty on duplicate) or a boolean success flag. The success path stays simple — `register` returns the created `Account` — and the web layer's error mapping catches this specific exception and maps it to 409, the same way it maps domain validation failures to 400.

Alternative considered: `Optional<Account>` return, empty on duplicate. Rejected — `Optional` reads as "might not exist," which is the semantics of a lookup (`findByCode`), not of a write that failed for a specific, nameable reason; reusing it here would blur that distinction. A boolean flag was rejected for losing the reason entirely.

### `Account` domain record has no `id` field; `createdAt` is nullable
`Account(code, ownerRef, type, currency, createdAt)` — five components, no surrogate `id`. This follows directly from the surrogate-key decision above: if `id` never leaves the database, the domain model above the JDBC adapter has no reason to carry it either, not even internally. `createdAt` is `Instant`, nullable: a not-yet-persisted `Account` (the shape `AccountPort.register` is called with) has `createdAt = null`, since the database assigns it via `DEFAULT now()`, not the application; the adapter reads the real value back (e.g. via `RETURNING created_at`) and returns a new `Account` with it populated. `currency` has no such problem — it's always `Currency.BRL` at every construction site, since that enum has exactly one value — so it's required, non-null, at construction.

Alternative considered: a separate pre-persistence type (e.g. `AccountRegistration`, without `currency`/`createdAt`) distinct from the persisted `Account`. This would make "not yet persisted" a compile-time-enforced state rather than a nullable field, but tasks.md scopes this change to a single "`Account` domain record," and a second type wasn't warranted for one nullable field.

### Hexagonal architecture, JDBC adapter instead of JPA
The domain package (`Account`, `AccountType`, `Currency`, `AccountPort`) has zero `org.springframework.*` / `jakarta.persistence.*` imports. Persistence is a JDBC adapter implementing `AccountPort`, not a JPA/Hibernate entity. Flyway is the sole schema owner; there is no JPA-managed DDL for this table. Consistent with that, no JPA/Hibernate dependency (e.g. `spring-boot-starter-data-jpa`) is added to the project at all — `spring-boot-starter-flyway` already brings in plain `spring-jdbc` (and therefore `JdbcTemplate`), which is all the JDBC adapter needs. There is accordingly no `spring.jpa.*` configuration anywhere in this slice; it would have nothing to act on.

Rationale: `entry_lines` becomes append-only in a later slice, and Hibernate's dirty-checking/managed-entity model actively works against an append-only, immutable-row table. Committing to JDBC now avoids migrating off JPA under pressure later.

Alternative considered: Spring Data JPA, for faster initial delivery. Rejected because the migration cost once `entry_lines` arrives is exactly the kind of expensive-to-change decision this design is front-loading.

### Testcontainers, no mocked persistence, no cleanup between tests
Integration tests run against real Postgres via Testcontainers with real Flyway migrations applied. Each test generates its own unique account `code` rather than relying on `TRUNCATE` or transactional rollback for isolation.

Rationale: this mirrors the append-only discipline the ledger will need from the next slice onward, and exercises the real migrations rather than a mocked persistence layer that could hide a broken migration behind a passing test.

### JSON wire format is snake_case; unrecognized fields are rejected, not ignored
`spring.jackson.property-naming-strategy: SNAKE_CASE` and `spring.jackson.deserialization.fail-on-unknown-properties: true` are set globally. Request/response field names (`owner_ref`, not `ownerRef`) match the database columns and the vocabulary every other artifact in this repo already uses; a client sending an unrecognized field name — including a differently-cased spelling of a real one — gets a 400, not a silent no-op.

Found the hard way: `RegisterAccountRequest.ownerRef` is a normal Java field, so by Jackson's un-configured default it only bound a JSON property literally spelled `ownerRef`. A client sending `owner_ref` (the spelling implied by every doc and the DB column) got a `201` with the field silently dropped — no error, no signal anything was wrong. This is the same "two representations of one fact, one of them a silent no-op" failure class as the `owner_ref` blank/padding handling above, just at the API-contract layer instead of the data layer, and it gets the same fix: reject the divergent representation outright rather than tolerate it quietly.

Alternative considered: `@JsonProperty("owner_ref")` on individual DTO fields instead of a global naming strategy. Rejected as the default going forward — it puts the burden on every future DTO across every later slice to remember the annotation, whereas a global naming strategy makes snake_case the path of least resistance for anyone adding a new field.

### Unknown-code lookup goes through the same exception-to-problem+json path as everything else
`AccountController.findByCode` throws `ResourceNotFoundException` (adapter.web package — a web-layer concept, not a domain one, since `AccountPort.findByCode` returning empty is a normal negative result, not an error; deciding that a lookup-by-key miss means 404 is an HTTP-contract choice) on a miss, caught by `ApiExceptionHandler` and mapped to 404 `problem+json`, rather than the controller building `ResponseEntity.notFound().build()` directly.

Found the same way as the naming-strategy issue: the original `ResponseEntity.notFound().build()` path bypassed `ApiExceptionHandler` entirely, so the 404 had no body and no `Content-Type` — contradicting the "all error responses ... for ... lookup" wording already in the spec. Every error path should go through one mapping mechanism; a handwritten `ResponseEntity` for one specific error is exactly the kind of per-case exception this project has otherwise avoided — `ApiExceptionHandler` was built generic specifically so no individual error path needs its own bespoke response construction.

### Catch-all `Exception` handler for unhandled failures, with a deliberately generic message
`ApiExceptionHandler` has an `@ExceptionHandler(Exception.class)` returning 500 `problem+json` with a fixed detail ("An unexpected error occurred") — unlike the other three handlers, which expose `ex.getMessage()` because those messages are hand-crafted by the domain layer specifically to be shown to a client. An unanticipated exception (found by literally stopping Postgres mid-request: a `DataAccessException` propagated uncaught, producing Spring Boot's default `/error` body — `application/json`, `{"timestamp",...,"status","error","path"}`, a different shape from every other error this API returns) could carry anything in its message — a connection string, a table name, a stack detail — none of which should reach a client. The exception is still logged server-side at ERROR level before the generic response is built, so nothing is lost for debugging, only withheld from the response body.

## Risks / Trade-offs

- [Two validation layers (Java regex + SQL `CHECK`) can drift if one is edited without the other] → mitigated by keeping both close together in the migration and domain code, with the anchoring difference called out explicitly at each site.
- [No cleanup between tests means the test database accumulates rows indefinitely] → acceptable: every test code is unique, so accumulation doesn't cause collisions. The real reason this is worth tracking isn't row count — it's that a later slice's global-invariant test (e.g. sum of all entry lines across the ledger = zero) will need to scan the whole table regardless of how many rows exist. Accumulation doesn't break that test, but it's worth knowing that constraint exists rather than assuming the no-cleanup policy is free simply because today's table is small.
- [`VARCHAR` + `CHECK` gives up native-enum type safety at the SQL level] → accepted; the domain enum is the real gate, so the `CHECK` losing DB-level type enforcement doesn't remove a layer that was doing real work.
- [Committing to a JDBC adapter over Spring Data JPA is more upfront code than a repository interface] → accepted; the alternative is a costlier migration once `entry_lines` needs append-only semantics.
- [Non-ASCII Unicode whitespace (e.g. U+2003 EM SPACE) inside a JSON string value is silently rewritten to a plain ASCII space somewhere before the domain layer ever sees it — confirmed via a value with such a character mid-string, not at a boundary, coming back altered with no error at all; root cause not yet identified] → deliberately deferred. This project's scope is a portfolio piece, not a production system handling real user data at stake — the time to root-cause a request-parsing-level Unicode behavior isn't proportionate here. Noted rather than silently dropped in case it resurfaces once free-text fields exist in a later slice.

## Migration Plan

New capability, no existing `accounts` data to migrate. Deploying this change applies Flyway `V1__create_accounts.sql` to create the table; rollback is dropping that (currently nonexistent) table, since nothing depends on it yet.

Running this locally requires `docker compose up` first to provision the Postgres instance the migration and app connect to — the migration doesn't run against anything until that instance exists.
