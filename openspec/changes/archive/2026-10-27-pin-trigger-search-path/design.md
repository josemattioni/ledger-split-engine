## Context

The ledger has five trigger functions, created in four migrations:

| Function | Migration | Check | Trigger |
|---|---|---|---|
| `check_entry_balance` | V2 | lines sum to zero (`LDG01`) | `AFTER INSERT` on `entry_lines`, deferred to commit |
| `forbid_ledger_mutation` | V3 (reused by V5) | append-only (`LDG02`) | `BEFORE UPDATE/DELETE/TRUNCATE` |
| `check_entry_has_enough_lines` | V3 | at least two lines (`LDG03`) | `AFTER INSERT` on `journal_entries`, deferred |
| `check_reversal_matches_original` | V4 | exact negation (`LDG04`), original not a reversal (`LDG05`) | `AFTER INSERT` on `journal_entries`, deferred |
| `check_line_added_with_its_entry` | V6 | line only with its entry (`LDG06`) | `BEFORE INSERT` on `entry_lines` |

Four of them read `entry_lines` or `journal_entries` by bare name (`forbid_ledger_mutation` names no table) and none sets a `search_path`, so each runs with the calling session's. Postgres searches `pg_temp` first for relations unless it is listed explicitly, `ledger_app` holds `TEMPORARY` on the database (the default for `PUBLIC`) and no `CREATE` on `public`, so a temporary table is the one place it can put an object that wins the lookup. `pg_restore` sets an empty search path, which is why the bare names also break a data-only restore. The project has one schema, `public` (Flyway's default).

**Observed** (throwaway Postgres 17, migrations V1 to V6, as `ledger_app`; see proposal.md for the attack):

| Case | V6 as it is | Search path pinned (`public, pg_temp`) |
|---|---|---|
| +5000/-5000 added to an earlier entry, `journal_entries` shadowed | committed (entry 1 went from 2 to 4 lines) | refused, `LDG06` |
| Unbalanced entry (+700/-600), `entry_lines` shadowed | committed (sum 100) | refused, "does not balance (sum = 100)" |
| `pg_restore --data-only`, triggers on, one transaction per table | fails: relation "entry_lines" does not exist (no header was restored) | fails: `LDG03` (a header is committed alone) |
| `pg_restore --data-only --single-transaction` | fails: relation "journal_entries" does not exist | **works** |
| `pg_restore --data-only --disable-triggers`; a full dump | works | works |

## Goals / Non-Goals

**Goals:**
- Every check reads the ledger's own tables whatever the session's search path, so no session can make one pass by shadowing a table.
- A function added later cannot forget: a test fails instead.
- A data-only restore in one transaction works with the triggers on, and the docs say so because a test runs it.

**Non-Goals:**
- Defending against the table owner, who can disable or drop the triggers (unchanged, documented).
- Changing a function body, a trigger, a table, a privilege or any data, or any application code.
- The 32-bit wraparound caveat of `LDG06` (theoretical, already documented), and the `owner_ref` Unicode backstop (stays deferred; it would now be `V8`).

## Decisions

### 1. Pin the search path with `ALTER FUNCTION`, in a new migration `V7`
`ALTER FUNCTION f() SET search_path = public, pg_temp;` for each of the five. A function-level setting applies whatever the session set, and listing `pg_temp` explicitly and last is what makes the real tables win (listing only `public` leaves `pg_temp` searched first for relations: the mutation check in tasks 3.4 shows it).

*Alternatives considered.* (a) Schema-qualify the table names in the bodies (`public.entry_lines`): equal protection for tables, but it rewrites four applied function bodies with `CREATE OR REPLACE` and leaves the next function free to forget; a pinned path can be checked mechanically (Decision 3). (b) `SET search_path = pg_catalog` plus qualifying every name: the strictest, and the widest edit. (c) Editing V2 to V4 and V6 in place: possible because nothing is committed, but it rewrites the checksum of four applied migrations for tidiness; a new migration keeps the history readable and applies cleanly to any database at V6. (d) `REVOKE TEMPORARY ON DATABASE … FROM PUBLIC` alone: it would close this route, but the functions would still depend on a privilege that lives outside the migrations (in the bootstrap script), it would not fix the restore, and it is irrelevant to the owner. Not done as an addition either (Decision 5).

### 2. `public` is written into the migration
`public, pg_temp` assumes the ledger tables live in `public`. If they did not, the functions would fail loudly at the first insert ("relation does not exist"), not silently, and the structural test pins the exact setting. Recorded as a trade-off rather than computed from `current_schema()`, which in a migration would reintroduce a dependency on the session.

### 3. Tests: the attack first, then a rule that catches the next function
- **Shadowing tests** (`TriggerFunctionsPinTheirSearchPathTest`, raw rows as `ledger_app` through `appConnection()`, real commits, no `@Transactional` rollback), one per check that reads a table: `LDG06`, `LDG01`, `LDG03`, `LDG04`, `LDG05`. Each has exactly one defect (the entry or line the check must refuse) and one shadow that would hide it; each asserts the exact SQLSTATE and that nothing was written. They are written before `V7` and must fail against V6 by committing, which is the evidence that the hole is real.
- **A structural test**: every function in `public` that returns `trigger` has `proconfig` equal to `search_path=public, pg_temp`, and the five known names are among them, so the test cannot pass on an empty set.
- **A restore test** (`RestoreRecipeTest`): in the shared test container, create two databases, migrate both with Flyway as the owner, seed one with accounts, an entry and its reversal, and run `pg_dump --data-only` and `pg_restore --data-only --single-transaction --exit-on-error` through `execInContainer`; the counts and a balance must match. A second test pins the contrast the docs state: the default per-table transactions are refused (`LDG03`), because the header is committed before its lines.

### 4. Documentation says what was tested
The restore text added after the `LDG06` review ("a data-only restore needs the triggers off") becomes the recipe the test runs. `V6`'s comment and the archived design of `close-final-review-gaps` are history and are not edited again; `V7`'s header says it lifts the restore limit they describe. `PROJECT-CONVENTIONS.md` gets the rule for new trigger functions.

### 5. Decisions made without the user's answer, to confirm at review
1. `V7` rather than editing the older migrations (Decision 1c).
2. No `REVOKE TEMPORARY`: with the path pinned, a temporary table is harmless, and the revoke would live in the bootstrap script (cluster-level, database-name-specific, only effective on a new volume). It can be added later without touching anything here.
3. `public` written into the migration (Decision 2).

## Risks / Trade-offs

- **[A function-level setting costs something per call]** A row trigger fires once per line (at most 100 per entry), and each line also fires the deferred balance check. → Measured (`pgbench`, one client, a 100-line entry per transaction, 200 transactions per run, Postgres 17 in Docker, phases alternated V6/V7/V6/V7 with the `V7` statements applied and undone with `RESET`): **4.12 ms per entry on V6 and 4.26 ms with the path pinned, averages of six runs each, +0.14 ms or about 3%**; the ranges overlap (V6 4.0 to 4.3, V7 4.2 to 4.3), so it is at the edge of what the run-to-run noise hides. About 200 trigger calls per entry makes it a fraction of a microsecond each. Accepted.
- **[A future trigger function forgets the pin]** → The structural test fails; the convention names the rule.
- **[The pinned path hides a missing schema]** → It does not: a wrong schema fails the first insert loudly (Decision 2).
- **[A volume created before this checkout]** `V6`'s comment was corrected in place (review finding F8), so its checksum changed. → `docker compose down -v` once; nothing is committed, and a test container is always new.
- **[Nothing changes for the owner]** The pin applies to every session, the owner's included, but the owner can still disable or drop a trigger. → Unchanged and documented ("Append-only guarantees and their limits").

## Migration Plan

`V7` is applied by Flyway at startup as the owner: five catalog updates, no table rewrite, no data change. There is no undo (Flyway Community has none, and undoing it reopens the hole). Order of work: failing tests, the migration, mutation checks, the restore test, docs, specs, full `verify`, a manual repeat of the attack on a recreated development database.
