## Why

This is slice 1 of a vertical-slice build of a marketplace payment split and settlement engine (sub-acquirer model, Brazilian card market — not Splitwise-style expense splitting). Every later slice (journal entries, splits, settlement) posts against ledger accounts, so before any money moves, the system needs a place to register the accounts it will post to: seller payables, marketplace commission, acquirer fees, clearing. This change also stands up the running application shell (hexagonal package structure, Flyway-owned schema, Postgres via Testcontainers/docker-compose, problem+json error convention) that every subsequent slice builds on.

## What Changes

- Add a Postgres-backed `accounts` table (Flyway `V1`) with columns `id`, `code`, `owner_ref`, `type`, `currency`, `created_at`.
- Add `POST /api/v1/accounts` to register an account (201 + `Location` header; 400 on malformed `code` or invalid `type`; 409 on duplicate `code`).
- Add `GET /api/v1/accounts/{code}` to fetch a registered account by its natural key (200 or 404).
- Establish the hexagonal package structure for the project: a domain package with zero `org.springframework.*` / `jakarta.persistence.*` imports, a JDBC adapter (no JPA) implementing an `AccountPort`, and a web adapter.
- Establish `application/problem+json` as the error response convention for this and all later slices.
- Add `docker-compose.yml` (Postgres 16) and a README with curl commands; the running app + curl walkthrough is the definition of done, not just green tests.
- Establish the integration test convention going forward: Testcontainers with real Postgres and real Flyway migrations, no mocked persistence, no rollback-based or TRUNCATE-based cleanup between tests.

**Explicitly out of scope for this change**: journal entries, entry lines, balance derivation, posting, reversal, idempotency keys, and settlement scheduling. These are reserved for later slices.

## Capabilities

### New Capabilities
- `account-registration`: registering a ledger account under a unique human-readable `code`, and looking it up by that `code`. Governs the account's accounting classification (`type`) and settlement currency, but not journal entries or balances.

### Modified Capabilities
(none — this is a greenfield project with no existing specs)

## Impact

- New code: domain model (`Account`, `AccountType`, `Currency`, `AccountPort`), JDBC adapter, web adapter (controller + problem+json error mapping), Flyway migration `V1__create_accounts.sql`.
- New project infrastructure: `docker-compose.yml`, README with curl-based verification steps, Testcontainers test dependency and base test setup.
- No existing application code is affected — the project is currently an empty Spring Boot skeleton (no controllers, entities, or migrations).
- No Party/Seller aggregate is introduced; `owner_ref` is an opaque reference into an external system, out of scope for this engine.
