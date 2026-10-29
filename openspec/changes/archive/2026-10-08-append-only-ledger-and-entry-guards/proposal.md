## Why

Slice 2 made the ledger balanced but not yet trustworthy: any code path (or anyone with the application's credentials) can still `UPDATE` or `DELETE` a posted row, and the entry shape is only loosely policed — an entry with no lines, a single line, a zero-amount line, or the same account twice is accepted today. This slice makes the ledger append-only, enforced by the database rather than by convention, and makes every entry a real movement of money. After it, a correction can only ever be a new entry (reversal, slice 5).

## What Changes

- **Two database roles.** Migrations run as the owner (the bootstrap Postgres user). The application connects as a new role `ledger_app` holding `SELECT` and `INSERT` only — no `UPDATE`, `DELETE` or `TRUNCATE` on any table, `accounts` included. `ledger_app` is created by an init script (roles are cluster-level, not schema objects) shared by `docker-compose` and Testcontainers; Flyway `V3` only grants privileges, including `ALTER DEFAULT PRIVILEGES` so later tables get the same grants.
- **Append-only triggers as a second layer.** One function `forbid_ledger_mutation()` (SQLSTATE `LDG02`) behind `BEFORE UPDATE OR DELETE … FOR EACH ROW` and `BEFORE TRUNCATE … FOR EACH STATEMENT` triggers on `journal_entries` and `entry_lines`. These stop the owner role and anything that gets past the grants. The owner can still disable them; that limit is documented, not hidden.
- **Degenerate-entry guards.** `JournalEntry` rejects, with a new `DegenerateJournalEntryException` mapped to **422**: fewer than 2 lines, any `amount_cents = 0`, and the same `account_code` on more than one line. `V3` adds database backstops: `CHECK (amount_cents <> 0)`, `UNIQUE (journal_entry_id, account_id)` on `entry_lines`, and a deferred constraint trigger on `journal_entries` (SQLSTATE `LDG03`) that rejects at commit an entry with fewer than 2 lines.
- **Error mapping.** The backstops (`LDG02`, `LDG03`, the new `CHECK`/`UNIQUE` violations) are unreachable through the API and surface as 500 + ERROR log via the existing catch-all. The slice 2 idempotency-key catch stays scoped to the header insert and `journal_entries_idempotency_key_uq`; the new `UNIQUE` is never translated to 409.
- **Distinct problem `type` per 422 rule.** Each ledger-rule violation (unbalanced entry, degenerate entry, unknown account, mixed currency, and the slice 2 amount limit) now carries its own `application/problem+json` `type`, so a client — and a test — can tell which rule fired from more than the status code. This also changes the `type` of slice 2's existing 422s from `about:blank`; no existing test or contract test asserts it.
- **Test hygiene.** Every negative test has exactly one defect and asserts the rule-specific `type` (and a rule-specific detail where one type covers several cases), so it cannot pass because a different rule fired. Slice 2 fixtures that used a lone line as a stand-in are corrected.
- **Configuration.** `spring.datasource.*` becomes `ledger_app`; `spring.flyway.user`/`password` stay the owner. `AbstractIntegrationTest` mirrors the split. The local Docker volume must be recreated (`docker compose down -v`), since init scripts only run on an empty data directory.
- **Docs and conventions.** `PROJECT-CONVENTIONS.md` gains the two-role rule and the append-only rule (no later slice may design anything that updates a row); `README.md` documents the roles, the volume reset, and the owner-can-disable-triggers limit.
- **Contract-suite harness (authorized edit):** `src/contractTest/.../ContractTestBase` starts its own container with the default superuser and no `ledger_app` role, so the whole contract suite would fail to boot under the new datasource/Flyway split. This change makes the one infrastructure edit that mirrors `AbstractIntegrationTest` (init script copied into the container, Flyway credentials split). No assertion in any contract test is touched: `sameAccountSeveralLines` and `sameAccountBothSides` encode the superseded "repeated account is accepted" behavior and are deliberately left red for QA to update from the new spec. See design.md.

**Out of scope** (later slices): idempotent replay (slice 4); reversal (slice 5); account field lock for the owner role, currency-grouped trigger, as-of balance (slice 6).

## Capabilities

### New Capabilities
- `ledger-append-only`: the guarantee that ledger rows, once written, cannot be updated, deleted or truncated — the application's database identity holds insert/select privileges only, and database triggers refuse mutation even from the migration owner — plus the separation between the migration owner and the application role.

### Modified Capabilities
- `journal-entries`: the "Post a balanced journal entry" requirement stops deferring degenerate shapes; new requirements reject an entry with fewer than two lines, a zero-amount line, or a repeated account (422), give every 422 ledger rule its own problem `type`, and state that storage-layer integrity backstop failures are internal errors (500), never conflicts.

## Impact

- **Migration:** new `V3__append_only_ledger.sql` (grants, default privileges, `forbid_ledger_mutation()` + four triggers, `entry_lines` CHECK and UNIQUE, `LDG03` function + deferred constraint trigger). New `docker/initdb/01-create-ledger-app-role.sql`; `docker-compose.yml` mounts it.
- **Config:** `application.yaml` datasource/Flyway credentials split. `LedgerSplitEngineApplicationTests` (compose Postgres) needs the recreated volume.
- **Code:** new `DegenerateJournalEntryException`; `JournalEntry` compact constructor; a small `ProblemTypes` constants class and per-exception 422 handlers in `ApiExceptionHandler`. No adapter change expected — `JdbcJournalEntryAdapter` already scopes its duplicate-key catch to the header insert.
- **Tests:** `AbstractIntegrationTest` (init-script container, split credentials, owner connection helper); new DB-level tests for grants, triggers and backstops; new/updated domain, controller and API tests asserting the problem `type`; slice 2 negative-test fixtures corrected to one defect each.
- **QA-owned:** `ContractTestBase` gets the single infrastructure edit above. `JournalEntryContractTest.sameAccountSeveralLines` and `sameAccountBothSides` (expect 201 for a repeated account within an entry — now 422) stay red for QA. `emptyLinesRejected` asserts `400 or 422` and so passes either way; the 422-vs-400 distinction is pinned by this change's own tests (the guard must live in the domain, not the DTO).
- **No change** to the public request/response shapes of existing endpoints beyond the new 422 cases.
