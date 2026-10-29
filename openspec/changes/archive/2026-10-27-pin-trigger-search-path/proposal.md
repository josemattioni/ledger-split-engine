## Why

The database backstops can be defeated by the identity they are meant to constrain. Every trigger function of the ledger names the tables it reads (`entry_lines`, `journal_entries`) without a schema and sets no `search_path`, and `ledger_app` holds `TEMPORARY` on the database. A temporary table with a ledger table's name is searched first, so the function reads the attacker's table instead. Reproduced as `ledger_app` on migrations V1 to V6, found while verifying a review finding about the `LDG06` comment:

- two lines that cancel out (+5000 / -5000) were committed into an entry from an earlier transaction, which is exactly what `LDG06` exists to refuse (`journal_entries` shadowed);
- an unbalanced entry (+700 / -600, sum 100) was committed, past the balance check `LDG01` and the two-line check `LDG03` (`entry_lines` shadowed).

The same unqualified names make a data-only `pg_restore` fail, because it runs with an empty `search_path`; `LDG06`'s notes and the docs recommended `--single-transaction`, which was never tried and does not work.

The attack needs a deliberate SQL session (the application uses parameterized JDBC and creates no temporary tables), so this is not an application defect. But the specs say `LDG06` refuses a line "for the application's database identity", and the README and conventions describe the triggers as backstops behind the domain, so the stated guarantees are wider than what holds today. Nothing is committed yet and the fix is five `ALTER FUNCTION` statements, so it is cheapest now.

## What Changes

- **A new migration `V7`** fixes the search path of the five trigger functions (`check_entry_balance`, `forbid_ledger_mutation`, `check_entry_has_enough_lines`, `check_reversal_matches_original`, `check_line_added_with_its_entry`) to `public, pg_temp`. Function bodies, triggers, tables, privileges and data do not change, and no application code changes.
- **A new requirement in `ledger-append-only`**: the database checks resolve their tables independently of the session's `search_path`, so no session can make a check pass by shadowing a ledger table, and every trigger function of the ledger fixes its own search path.
- **Tests.** Raw-row tests as `ledger_app` for each check that reads a table (`LDG06`, `LDG01`, `LDG03`, `LDG04`, `LDG05`), each written first and shown failing against V6, plus a structural test that every trigger function in the schema fixes its search path, so a function added later cannot forget. A restore test runs `pg_dump` and `pg_restore --data-only --single-transaction` against a migrated database with the triggers on.
- **Docs.** `docs/ledger-guarantees.md` and `PROJECT-CONVENTIONS.md` state the search-path rule and the restore recipe that now works, and the README's guarantees section says the checks hold against a shadowing session. `V6`'s own comment and the archived `design.md` are history and stay as they are; `V7`'s header says it lifts the restore limit they describe.
- **Not in this change**, recorded in design.md: `REVOKE TEMPORARY` (the pinned path makes temporary tables harmless, and the revoke would live in the bootstrap script); the 32-bit `xmin` wraparound caveat of `LDG06` (theoretical, documented); the `owner_ref` Unicode backstop (stays deferred; it would now be `V8`).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ledger-append-only`: a new requirement that the database checks cannot be redirected by the session's search path.

## Impact

- **Database:** `V7__pin_trigger_function_search_path.sql` (five `ALTER FUNCTION … SET search_path`). A volume created by an earlier checkout needs `docker compose down -v` once (the `V6` comment was corrected in place, which changes its Flyway checksum; nothing is committed yet).
- **Tests:** new classes in `src/test` (`TriggerFunctionsPinTheirSearchPathTest`, `RestoreRecipeTest`). `src/contractTest` is QA's and is not touched; its result is expected to stay at 937 invocations, 29 skipped, 0 failures, since nothing observable over HTTP changes.
- **Docs:** `docs/ledger-guarantees.md`, `PROJECT-CONVENTIONS.md`, `README.md`.
- **Specs:** `openspec/specs/ledger-append-only/spec.md` at archive time.
