## Context

Slices 1–2 gave the engine accounts, journal entries and a deferred balance trigger (`V1`, `V2`). Today one credential does everything: `application.yaml` points both the app and Flyway at the bootstrap Postgres user (`ledger_split_engine`), which also owns every table. That user can `UPDATE`/`DELETE`/`TRUNCATE` any ledger row, and nothing in the schema stops it. `JournalEntry`'s compact constructor already enforces key shape, line numbering, the per-line amount cap and the zero-sum rule, but deliberately not entry *shape* (see the archived `add-journal-entries` design, "Non-Goals"). The README/spec both say so explicitly, which is why `journal-entries`' first requirement is a MODIFIED delta here.

Two constraints from earlier slices matter again:

- **Triggers are not enough.** A table owner can `ALTER TABLE … DISABLE TRIGGER`, so append-only has to rest on privileges first and triggers second.
- **`src/contractTest` is QA-owned.** Its `ContractTestBase` starts its own `postgres:16` with the default `test` superuser and overrides only `spring.datasource.*`. Under the new split that base class cannot boot the app; the user has authorized one infrastructure-only edit to it (Decision 9). Its assertions stay QA's.

See proposal.md for motivation; specs for requirements.

## Goals / Non-Goals

**Goals:**
- Make "no `UPDATE`/`DELETE`/`TRUNCATE` on the ledger" true by privileges for the application, and true by trigger for everyone else (the owner included, short of disabling the triggers).
- Make a degenerate entry unrepresentable: rejected in the domain with a 422 first, with database backstops that turn a bypass into a loud 500.
- Keep one mechanism for the `ledger_app` role in docker-compose and in Testcontainers, so the dev database and the test database cannot drift.

**Non-Goals:**
- Preventing the owner role from disabling triggers or dropping them. That is the documented limit; the owner is for migrations only.
- Locking `accounts` fields against the owner (slice 6). `ledger_app` already cannot `UPDATE` `accounts` by grants; no trigger is added on `accounts` here.
- Any change to idempotent replay (slice 4) or reversal (slice 5), including the `reverses_entry_id` design, beyond recording the "never update a row" convention.
- Secret management for the `ledger_app` password. It is a dev-only constant in the init script and `application.yaml`.

## Decisions

### 1. `ledger_app` is created by an init script, not by Flyway
`docker/initdb/01-create-ledger-app-role.sql` creates `ledger_app` (`LOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, dev password) and nothing else. It is mounted into `/docker-entrypoint-initdb.d` by `docker-compose.yml`, and copied to the same path in the Testcontainers container (`withCopyFileToContainer(MountableFile.forHostPath("docker/initdb/01-create-ledger-app-role.sql"), …)`). Same file, same Postgres entrypoint mechanism, in both places.

- **Why not a Flyway migration?** Roles are cluster-level, not schema objects: the migration owner would need `CREATEROLE`, and the migration would fail the second time it ran against another database in the same cluster. A migration granting privileges to a role that must already exist is the clean split.
- **Why not Testcontainers `withInitScript`?** It runs the script over JDBC after startup — a different mechanism from the entrypoint's `psql` run, which is exactly where the two environments would drift (e.g. which database/user the script runs as).
- The host path is relative to the JVM working directory; Maven's surefire/failsafe run from the project root. Running a test from an IDE with another working directory would fail to find the file — acceptable, and called out in Risks.

### 2. V3 grants only; identity columns need no sequence grant
`V3` starts with `GRANT SELECT, INSERT ON accounts, journal_entries, entry_lines TO ledger_app;` and `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES TO ledger_app;`.

- `ALTER DEFAULT PRIVILEGES` without `FOR ROLE` applies to objects created by the role running it — the migration owner, which is the only role that creates tables. `flyway_schema_history` already exists when `V3` runs, so it is untouched and stays invisible to `ledger_app`.
- No `UPDATE`/`DELETE`/`TRUNCATE`/`REFERENCES`/`TRIGGER` is granted. No `REVOKE` is needed: new tables have no `PUBLIC` privileges.
- **Sequences (to verify, per the brief):** all three tables use `GENERATED ALWAYS AS IDENTITY`. Postgres does not check `USAGE` on an identity column's implicit sequence when an `INSERT` generates the value, so `ledger_app` should need no sequence grant. The positive control (a balanced entry inserted and committed as `ledger_app`) proves it. If it fails with a sequence permission error, add `GRANT USAGE ON ALL SEQUENCES` plus the matching default privilege to `V3`.
- Schema `public`: `ledger_app` relies on the default `USAGE` that `PUBLIC` holds (Postgres 16 removed only `CREATE`). That also means `ledger_app` cannot create objects, which a test pins down.
- Trigger functions run as the invoking user, so `check_entry_balance()` (and the new `LDG03` function) read `entry_lines` as `ledger_app` — covered by the `SELECT` grant. Foreign-key checks run as the table owner.

### 3. Credentials split in configuration
`spring.datasource.username/password` → `ledger_app`; `spring.flyway.user/password` → the owner (`ledger_split_engine`). `AbstractIntegrationTest` registers the same split via `@DynamicPropertySource` (datasource = `ledger_app`, Flyway = the container's superuser).

- **Assumption, confirmed in apply:** Boot's Flyway auto-configuration builds the migration `DataSource` from `spring.flyway.user/password` plus the datasource URL when `spring.flyway.url` is unset. `flyway_schema_history.installed_by` is the owner for every migration (on the compose database and, via `LedgerAppPrivilegesTest`, on the Testcontainers one), so no `spring.flyway.url` is needed.
- `LedgerSplitEngineApplicationTests` (uses the compose Postgres on 5433, not Testcontainers) needs the recreated volume (`docker compose down -v`), because init scripts only run on an empty data directory. Without it, the app starts against a cluster with no `ledger_app` and Flyway's `V3` fails with `role "ledger_app" does not exist` — a clear enough message that no guard is added in `V3`.

### 4. Append-only triggers: one function, four triggers
```sql
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the ledger is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'LDG02';
END;
$$ LANGUAGE plpgsql;

-- for each of journal_entries, entry_lines:
CREATE TRIGGER trg_<table>_append_only
    BEFORE UPDATE OR DELETE ON <table>
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
CREATE TRIGGER trg_<table>_no_truncate
    BEFORE TRUNCATE ON <table>
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_mutation();
```
`TRUNCATE` does not fire row-level triggers, hence the separate statement-level trigger. `accounts` gets no trigger (Non-Goals).

Two behaviors the tests have to respect, both consequences of how Postgres works rather than gaps in the design:
- **Row-level triggers fire only for rows that exist.** `DELETE FROM journal_entries WHERE false` raises nothing and changes nothing. The tests first commit a real entry and then target its row.
- **`TRUNCATE` checks foreign-key references before firing its `BEFORE` trigger.** A plain `TRUNCATE journal_entries` is refused because `entry_lines` references it, before `LDG02` can fire. The owner-side test therefore truncates `journal_entries, entry_lines` together (both listed, so the reference check passes and the trigger fires), and `TRUNCATE entry_lines` alone. The append-only property is not weakened: the bare form fails regardless. If empirical behavior differs, the test adapts, not the design.

The trigger must fire for the owner on purpose: that is its entire job. It stops the owner and anything that slips past the grants; it does not stop the owner from `DISABLE TRIGGER` or `DROP TRIGGER` — documented in the README rather than hidden.

### 5. Degenerate guards in the domain, in a fixed order
New `DegenerateJournalEntryException` (domain package, unchecked, mapped to 422 alongside the existing ledger-rule exceptions). `JournalEntry`'s compact constructor keeps its current order and inserts the new checks after the structural ones and before the cap:

1. null checks, `List.copyOf`, idempotency-key shape, line-number sequence — `IllegalArgumentException` → 400 (unchanged)
2. fewer than 2 lines → `DegenerateJournalEntryException`
3. any `amount_cents == 0` → `DegenerateJournalEntryException` (names the line)
4. an `account_code` appearing on more than one line → `DegenerateJournalEntryException` (names the code)
5. per-line cap → `AmountLimitExceededException` (unchanged)
6. sum ≠ 0 / overflow → `UnbalancedJournalEntryException` (unchanged)

- **Consequence worth stating:** a one-line entry of 100 is now reported as "needs at least two lines" rather than "unbalanced". Both are 422, and the check order is the only thing that decides it.
- **Repeated accounts compare `account_code` strings exactly.** `code` is the accounts table's natural key, so equal strings ⇔ same account; no normalization is needed (a differently-cased or padded string is an unknown account and fails with its own 422 in the adapter). This also means the rule needs no I/O and lives in the domain, like the other pure rules.
- **Alternative rejected:** Bean Validation on the request DTO. It would give a 400, but this is a ledger rule (well-formed request, invalid entry), so it belongs in the domain type and maps to 422, consistent with slice 2's two-layer split. Concretely, `PostJournalEntryRequest` keeps `@NotNull` on `lines` and gains **no** `@NotEmpty` or `@Size(min = 2)`: `{"lines":[]}` must reach the domain constructor. A `400` for an empty array would mean the guard landed in the DTO; the end-to-end and controller tests pin `422` plus the `degenerate-entry` type for exactly this input.
- **Detail wording** (asserted by tests as substrings, so the three cases can't be confused under their shared type): `journal entry must have at least two lines (got N)`; `line N has a zero amount_cents`; `account_code X appears on more than one line (lines I and J)`. None contains the word "Exception" or a package name (the QA support code rejects those in `detail`).
- Duplicate detection runs *before* the cap and the sum on purpose: `[+100 a, +50 a, −150 b]` is balanced and in range, so only this rule can catch it.

### 6. Database backstops, named
In `V3`:
```sql
ALTER TABLE entry_lines
    ADD CONSTRAINT entry_lines_amount_nonzero_ck CHECK (amount_cents <> 0),
    ADD CONSTRAINT entry_lines_entry_account_uq UNIQUE (journal_entry_id, account_id);

CREATE FUNCTION check_entry_has_enough_lines() RETURNS trigger AS $$
DECLARE
    line_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO line_count FROM entry_lines WHERE journal_entry_id = NEW.id;
    IF line_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % line(s); at least 2 are required', NEW.id, line_count
            USING ERRCODE = 'LDG03';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entries_min_lines
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_has_enough_lines();
```
- **Why the line-count check is on `journal_entries`:** a header with no lines never fires `trg_entry_lines_balance` (it is `AFTER INSERT ON entry_lines`), so the only place to catch it is on the header, deferred to commit so the lines can be inserted after it in the same transaction.
- **Explicit constraint names** follow the slice 2 precedent (`journal_entries_idempotency_key_uq`): the adapter must be able to tell these violations apart by name, and tests assert the name rather than just the SQLSTATE.
- **Event-queue order (verify empirically):** deferred trigger events fire at commit in the order they were queued, and the header's event is queued before any line's. A one-line entry should therefore surface `LDG03`, not `LDG01`, even though it is also unbalanced. Tests for "a one-line entry cannot commit" assert the SQLSTATE is one of `LDG01`/`LDG03` so they pin the guarantee, not the queue order; the dedicated `LDG03` test uses a zero-line header, where only `LDG03` can apply.
- **`SET CONSTRAINTS ALL IMMEDIATE` after the lines** (as the existing slice 2 immediate-mode tests do) still passes: the header's deferred event fires then, with its lines already inserted. Switching to `IMMEDIATE` *before* the lines would fail the header check; the adapter never does that.
- `V3`'s `ADD CONSTRAINT` validates existing rows, so a database that already holds degenerate rows fails the migration loudly. See Risks.

### 7. Error mapping: one new 422 exception, a distinct problem `type` per 422 rule, no new 500 handlers
- **Problem types.** Today every 422 is `ProblemDetail.forStatusAndDetail(...)`, i.e. `type: about:blank`, so a test (or client) that asserts only "422 + problem+json" passes whichever rule fired. Each ledger rule gets its own `type`, defined once as constants in a new `adapter.web.ProblemTypes` (the domain stays free of URIs):

  | Exception | `type` |
  |---|---|
  | `UnbalancedJournalEntryException` (also the overflowing sum) | `urn:ledger-split-engine:problem:unbalanced-entry` |
  | `DegenerateJournalEntryException` | `urn:ledger-split-engine:problem:degenerate-entry` |
  | `UnknownAccountException` | `urn:ledger-split-engine:problem:unknown-account` |
  | `MixedCurrencyException` | `urn:ledger-split-engine:problem:mixed-currency` |
  | `AmountLimitExceededException` | `urn:ledger-split-engine:problem:amount-limit-exceeded` |

  The multi-exception `handleLedgerRuleViolation` becomes one handler method per exception, each calling a small helper that builds the 422 `ProblemDetail` and calls `setType(...)`. The detail text is unchanged.
- **`amount-limit-exceeded` is a fifth type beyond the four named in review.** The cap is a 422 rule too; leaving it on `about:blank` would break the "distinct type per rule" property for exactly the rule most likely to be confused with the unbalanced one (the cap is checked first, and an over-limit entry that also doesn't balance reports the cap).
- **URN rather than an HTTP URL.** RFC 9457 allows any URI reference. The project owns no domain, a relative reference would resolve against each request URI, and a made-up `https://` URL would look dereferenceable and isn't. A URN is stable, opaque and honest.
- **Scope.** Only the 422 tier gets types. 400/404/409/500 keep `about:blank`: nothing needs to tell them apart beyond the status, and widening this is a separate convention decision. Degenerate's three cases deliberately share one type (per review); the `detail` substring distinguishes them.
- **Safe to introduce:** no unit test and no contract test asserts the `type` member today (checked), and the QA leak check applies to `detail` only.
- `ApiExceptionHandler`'s 422 handlers gain `DegenerateJournalEntryException`.
- `LDG02`, `LDG03` and the new `CHECK`/`UNIQUE` violations need no new handler. They reach the existing `Exception` catch-all as, respectively, `UncategorizedSQLException`/`TransactionSystemException` (commit-time) and `DataIntegrityViolationException`/`DuplicateKeyException` — all become 500 `application/problem+json` with the generic detail, plus the existing `log.error`.
- **Idempotency catch unchanged.** `JdbcJournalEntryAdapter.insertHeader` catches `DuplicateKeyException` only around the header insert and only converts it when the violated constraint is `journal_entries_idempotency_key_uq`. The new `UNIQUE (journal_entry_id, account_id)` can only fire in `insertLines`, outside that `try`, and carries a different constraint name — so it propagates as the 500 it should be. A controller-level test pins that a `DuplicateKeyException` from the port yields 500, not 409.
- No adapter code change is planned. If implementation finds otherwise, it is a design update, not a silent edit.

### 8. Tests: real commits, two connections
- `AbstractIntegrationTest` keeps the singleton container, now built with the init-script copy. It exposes the owner connection (`ownerJdbc()` over `DriverManagerDataSource` with the container's superuser) alongside the autowired `JdbcTemplate`, which is `ledger_app`. Role names and the dev password are constants shared with the init script's contents.
- New classes, all in `adapter.persistence.journalentry` next to `EntryLinesBalanceTriggerTest`, none using `@Transactional` rollback (deferred triggers only fire at a real `COMMIT`):
  - `LedgerAppPrivilegesTest` — the app datasource is `ledger_app`; migration history `installed_by` is the owner; `ledger_app` gets `42501` for `UPDATE`/`DELETE`/`TRUNCATE` on `accounts`, `journal_entries`, `entry_lines`, and for `CREATE TABLE`; positive control (a balanced 2-line entry inserts and commits, proving identity columns need no sequence grant); a scratch table created by the owner afterwards is `SELECT`/`INSERT`-only for `ledger_app` (default privileges), checked with `has_table_privilege`, then dropped.
  - `AppendOnlyTriggersTest` — as the owner, `UPDATE`/`DELETE` of an existing committed row and `TRUNCATE` (per Decision 4) on each of the two tables fail with `LDG02`.
  - `DegenerateEntryBackstopsTest` — zero-line header committed → `LDG03`; one-line entry cannot commit (`LDG01` or `LDG03`); `amount_cents = 0` → `23514` at insert (constraint `entry_lines_amount_nonzero_ck`); same account twice → `23505` at insert (constraint `entry_lines_entry_account_uq`, not the idempotency constraint).
- Domain unit tests: `JournalEntryTest` gains 0-line, 1-line, zero-amount and repeated-account cases and a valid 2-line case, each asserting the exception type and the rule-specific detail substring. Controller tests (MockMvc, real domain constructor) for each degenerate shape → 422 with the `degenerate-entry` type, and for a backstop-style exception from the port → 500. An end-to-end API test per degenerate shape (422, `degenerate-entry` type, balances untouched).
- **One defect per negative test.** Every negative test has exactly one defect and asserts something rule-specific beyond the status: the problem `type`, plus a detail substring where one type covers several cases (the three degenerate cases). Fixtures are otherwise fully valid: at least two lines, distinct accounts, non-zero, in range, balanced, one `Idempotency-Key`. Two kinds of test legitimately break this and are named for it:
  - *Precedence tests* pin which rule wins when two apply (e.g. an over-limit line that also doesn't balance reports the cap; a lone line reports the two-line minimum, not an imbalance). A one-line entry is inherently two-defect — its only line is either zero or unbalanced — so the one-line case is always a precedence test, and says so.
  - *Backstop tests* (DB level) bypass the domain on purpose, and assert the SQLSTATE and constraint name.
- **Existing slice 2 tests, audited against that rule.** `EntryLinesBalanceTriggerTest` (including the overflow test's three lines, which already use three distinct accounts), `JdbcJournalEntryAdapterTest`, the unknown-account/duplicate-key/string-amount/two-header tests and the controller tests' `validBody()` already satisfy it and need no change. Fixtures that do not:
  - `JournalEntryApiIntegrationTest.unbalancedEntryReturns422ProblemJson`: a lone line, which would now pass via the two-line rule. Becomes two valid distinct lines of `+100` / `-99`, asserting the `unbalanced-entry` type.
  - `JournalEntryControllerTest`: `missingIdempotencyKeyHeaderReturns400ProblemJson` (lone zero-amount line), `lineMissingAmountCentsReturns400ProblemJson`, `nonIntegralAmountCentsReturns400ProblemJson`, `nullAccountCodeReturns400ProblemJson` and `nullElementInLinesReturns400ProblemJson` (all lone-line bodies). Each gets a second valid line so the named defect is the only one; they still return 400 at binding, before the domain.
  - `JournalEntryControllerTest.unbalancedJournalEntryReturns422ProblemJson` and `amountLimitExceededReturns422ProblemJson` stub the port to throw a domain exception the port never throws (the domain constructor throws them before the port is reached). They become real bodies (`+100`/`-99`; `±2_000_000_000_000`) with no stub, so they exercise the real path. `unknownAccount…` and `mixedCurrency…` keep their stubbed port (adapter-thrown rules; mixed currency cannot be produced with real data while `BRL` is the only currency).
  - `JournalEntryTest`: `balancedSingleLine()` (a lone zero-amount line, used by the key-shape tests, which pass today only because the key check runs first) and the `[zero-amount line, null]` fixture in `rejectsLinesContainingNullElement` become valid lines plus the one defect under test. `rejectsLongMaxLinesAsOverTheLimitBeforeTheyAreSummed` and `reportsAnOverLimitLineBeforeAnUnbalancedSum` are precedence tests and are renamed/commented as such.

### 9. The contract suite's harness gets one infrastructure edit; its assertions stay QA's
After this change `application.yaml` points Flyway at `ledger_split_engine`, but `ContractTestBase` starts a container whose only user is `test` and has no `ledger_app`. Without an edit the app fails to start for **every** contract test. The user has authorized the fix as harness infrastructure, mirroring `AbstractIntegrationTest`:
- **Edit scope — `ContractTestBase` only, infrastructure only:** build the container with the same init-script copy, register `spring.datasource.*` as `ledger_app`, and register `spring.flyway.user/password` as the container user. No assertion, helper or expectation in any contract test changes.
- **`sameAccountSeveralLines` and `sameAccountBothSides` are left unchanged and red.** They expect 201 for a repeated account within one entry, which the new spec forbids; QA updates them from the new spec.
- **`emptyLinesRejected`** asserts `assertProblemIn(r, 400, 422)`, so it passes under either status and cannot by itself show where the guard lives. A `400` would mean the guard landed in the DTO; `422` is correct. This change's own tests pin `422` plus the `degenerate-entry` type for `{"lines":[]}`, and the apply step reports the status the running app actually returns.
- The apply step runs `./mvnw verify` after the edit and reports every contract failure against the slice 2 baseline of 20 explained failures rather than predicting the list: the expected deltas are `emptyLinesRejected` newly green and the two same-account tests newly red.

## Risks / Trade-offs

- [Owner can disable or drop the triggers] → Documented in the README ("Append-only guarantees and their limits"); the owner role is for migrations only and the app never holds it. Privileges, not triggers, are the primary control.
- [`V3`'s `CHECK`/`UNIQUE` are validated against existing rows] → A database holding slice 2 degenerate entries (zero amounts, repeated accounts) fails `V3` loudly. Dev volumes are recreated anyway (`down -v`), Testcontainers is always fresh, and there is no production data; a real legacy dataset would need an audit first. Zero-line legacy headers are tolerated (`LDG03` only fires on new inserts).
- [Init scripts only run on an empty data directory] → README documents `docker compose down -v`; the failure without it is Postgres's own `role "ledger_app" does not exist`.
- [`V3` is built up across several apply sections] → If edited after the compose database already applied it, Flyway reports a checksum mismatch. Recreate the volume (`docker compose down -v`) after editing `V3`; Testcontainers is unaffected.
- [Init-script path is relative to the JVM working directory] → Works under Maven; an IDE run from another directory fails to find the file with a clear error. Not worth a classpath copy of the script for a portfolio project.
- [Hardcoded dev password for `ledger_app`] → Dev-only, mirrored in `application.yaml` like the owner's. Secret injection is out of scope.
- [Event-queue ordering and `TRUNCATE` FK-check ordering are Postgres behaviors] → Both were observed on Postgres 16 during apply and match the reasoning: a one-line entry raises `LDG03` (not `LDG01`) at commit; a bare `TRUNCATE journal_entries` is refused with `0A000` by the foreign-key check before the trigger, while `TRUNCATE entry_lines`, `TRUNCATE journal_entries, entry_lines` and `TRUNCATE journal_entries CASCADE` all raise `LDG02`; a zero-row `DELETE` raises nothing. Tests still assert the guarantee rather than the ordering (`LDG01`-or-`LDG03`), so a future Postgres change in event order would not break them.
- [Two contract tests stay red by design] → `sameAccountSeveralLines` and `sameAccountBothSides` encode superseded behavior; left for QA to update from the new spec (Decision 9) and called out in the final report so the red is not mistaken for a regression.
- [A `ContractTestBase` edit touches QA-owned code] → Limited to harness infrastructure by explicit authorization; the diff is reviewed to contain no assertion changes.
- [`LDG03` adds one `COUNT` per posted entry at commit] → Negligible: it is an indexed lookup on `(journal_entry_id, …)` and entries are small.

## Migration Plan

1. `docker compose down -v && docker compose up -d` (recreates the volume so the init script runs), then start the app. Flyway applies `V1`–`V3` as the owner; the app then connects as `ledger_app`.
2. Rollback: Flyway community has no undo. For a dev database, recreate the volume from the previous commit; for anything else, a corrective `V4` — never an edit to `V3`, and never by the app role, which cannot do DDL.
