## Context

Slice 1 (`accounts-and-skeleton`) established the patterns this slice reuses directly: hexagonal package-per-capability layout (`domain.account`, `adapter.persistence.account`, `adapter.web.account`), a JDBC adapter (no JPA) as the only persistence mechanism, Flyway as sole schema owner, `application/problem+json` via a single `@RestControllerAdvice`, two-layer validation (domain first, SQL constraint as backstop), and server-assigned values (`created_at`, `currency`) read back via `RETURNING` rather than round-tripped from the client. See `openspec/changes/accounts-and-skeleton/design.md` for the full rationale behind each; this design only calls out where slice 2 follows the same pattern or deliberately departs from it.

A contract-test harness already exists (`src/contractTest`, REST-Assured against a real app + Testcontainers Postgres, run by `mvn verify`) and a full black-box suite for `account-registration` already lives there. Per the input spec, the black-box contract tests for this slice's endpoints are written in a separate QA pass from `specs/journal-entries/spec.md` and `specs/account-balances/spec.md`, not as an implementation task here — this design and tasks.md scope only the adapter-level tests that HTTP can't reach (the deferred trigger).

## Goals / Non-Goals

**Goals:**
- Get `journal_entries` / `entry_lines` right on the dimensions later slices build on directly: the deferred-trigger balance backstop, the append-only-friendly column set (`line_number`, `idempotency_key` already present even though slice 4 owns idempotent-replay behavior), and the FK shape into `accounts.id`.
- Keep the same validation split as slice 1: reject everything reject-able without I/O in the domain layer first, use the database only for what genuinely needs it (referential integrity, the balance backstop, idempotency-key uniqueness under concurrency).

**Non-Goals:**
- Append-only enforcement (no update/delete trigger on `entry_lines`/`journal_entries` yet) — slice 3.
- Degenerate-entry guards: nothing in this slice rejects a zero-line entry, a one-line entry, a zero-amount line, or two lines against the same account. A zero-line entry trivially sums to zero today and will succeed; this is a known, deliberately deferred gap, not an oversight (see Risks).
- Idempotent replay (returning the original response for a repeated key) — slice 4 detects this by comparing the request against the stored entry; this slice only turns a repeated key into a 409.
- Reversal, currency-grouped balance, as-of balance, account field locking — slices 5–6.

## Decisions

### Schema: `journal_entries` and `entry_lines`, exactly as specified, with four additions
```sql
CREATE TABLE journal_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL
        CHECK (idempotency_key ~ '^[!-~]{1,255}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT journal_entries_idempotency_key_uq UNIQUE (idempotency_key)
);

CREATE TABLE entry_lines (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entries (id),
    line_number INT NOT NULL CHECK (line_number >= 1),
    account_id BIGINT NOT NULL REFERENCES accounts (id),
    amount_cents BIGINT NOT NULL,
    UNIQUE (journal_entry_id, line_number)
);

CREATE INDEX entry_lines_account_id_idx ON entry_lines (account_id);
```
- `entry_id UUID ... DEFAULT gen_random_uuid()`: the input spec doesn't say who generates it. Following the `created_at`/`currency` precedent (server-assigned values are DB column defaults, read back via `RETURNING`), `entry_id` is generated the same way. Postgres 13+ (we're on 16) has `gen_random_uuid()` built in, no extension needed.
- `idempotency_key`'s `CHECK` is an addition beyond the input spec's literal column list (`VARCHAR(255) NOT NULL UNIQUE`), added for consistency with the two-layer validation convention already applied to `code` and `owner_ref`: `[!-~]` is a character range, not a named POSIX class — it matches any byte from `!` (`0x21`) to `~` (`0x7E`), i.e. printable ASCII excluding space, which is what "visible ASCII" means here. This is a backstop only — the domain layer is still the primary gate and produces the 400.
- The unique constraint on `idempotency_key` is given an explicit name, `journal_entries_idempotency_key_uq`, instead of Postgres's auto-generated one. The adapter needs to tell this violation apart from any other unique-constraint violation it might catch (see the idempotency-key decision below), and an auto-generated name is an implementation detail that shouldn't be load-bearing.
- `entry_lines_account_id_idx`: Postgres does not automatically index a foreign-key column (only the referenced side gets one, via the primary key). `UNIQUE (journal_entry_id, line_number)` covers the deferred trigger's own per-entry lookup, but nothing covered the balance query below, which filters `entry_lines` by `account_id` — without this index, every balance read would be a full table scan.
- `line_number`/`idempotency_key` exist on this migration even though their full behavioral contract (append-only immutability, replay) belongs to slice 3/4 — per the input spec, because slice 3 makes these tables append-only, so adding these columns later would need a per-row backfill blocked by the project's own trigger.

### Deferred constraint trigger enforces balance at commit
```sql
CREATE FUNCTION check_entry_balance() RETURNS trigger AS $$
DECLARE
    entry_total NUMERIC;
BEGIN
    SELECT SUM(amount_cents) INTO entry_total
    FROM entry_lines
    WHERE journal_entry_id = NEW.journal_entry_id;

    IF entry_total <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance (sum = %)', NEW.journal_entry_id, entry_total
            USING ERRCODE = 'LDG01';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entry_lines_balance
    AFTER INSERT ON entry_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entry_balance();
```
Postgres constraint triggers can only be `FOR EACH ROW` (a `FOR EACH STATEMENT` constraint trigger isn't legal), so re-summing once per inserted row — redundant for a multi-line entry, but the only form the deferred-constraint mechanism supports — is not a choice, it's the only shape available.

`entry_total` is declared `NUMERIC`, not `BIGINT`. `SUM(bigint_column)` in Postgres returns `numeric` precisely so the aggregate itself can't overflow; assigning that result into a `BIGINT` variable reintroduces the overflow risk the aggregate was avoiding — for lines like `9223372036854775807`, `9223372036854775807`, `2` (each individually a valid `amount_cents`, but summing past `bigint`'s range), that assignment would raise Postgres's own `22003` (numeric value out of range) *before* the `IF entry_total <> 0` check ever runs, masking the failure as a generic overflow error instead of the intended `LDG01`. `NUMERIC` has no such ceiling, so the balance check itself always runs and always reports `LDG01` for any nonzero sum, overflowing or not.

`LDG01` is a custom SQLSTATE, not a standard one — but not because it contains letters (standard codes do too, e.g. `42P01`, `XX000`). The SQL standard reserves error-class prefixes starting with the letters `I`–`Z` for implementation-defined use; Postgres's own built-in error-code table (see its documentation appendix) has no class `LD`, so this code can't collide with anything Postgres already defines.

The domain layer (below) rejects an unbalanced entry before any row is ever inserted, so this trigger should never fire in normal operation. It exists as the backstop for the same reason `accounts`' `CHECK` constraints exist: to catch anything written outside the domain's own gate, not as the primary contract.

### Request DTO uses boxed types and cascading validation, not primitives
`PostJournalEntryRequest` is `record PostJournalEntryRequest(@NotNull List<@NotNull @Valid LineRequest> lines)`, with a nested `record LineRequest(@NotBlank String accountCode, @NotNull Long amountCents)`. Both `LineRequest` fields are boxed (`Long`, not `long`), because a primitive `long` bound from a JSON line object missing `amount_cents` would silently default to `0` — for a value that later gets summed and balance-checked, that's actively dangerous: two lines each missing `amount_cents` would both bind as `0`, sum to zero, and post successfully as a 201, with no indication anything was wrong.

The `@NotNull` on the *type parameter* (`List<@NotNull @Valid LineRequest>`), not just on the field, is deliberate: `@Valid` alone cascades Bean Validation into each non-null list element's own constraints, but a null element has nothing to cascade into, so `@Valid` alone lets `"lines": [null]` straight through, past the DTO layer, to hit a `NullPointerException` when the controller reads that element — a 500, not a 400. Placing `@NotNull` on the type parameter (a Jakarta Bean Validation "container element constraint," supported since Bean Validation 2.0) rejects a null element directly, before the controller ever sees it. The outer field's own `@NotNull` still separately rejects a missing/null `lines` key entirely.

`accountCode` is `@NotBlank`, not just `@NotNull`, matching `RegisterAccountRequest.code`'s treatment of a required string identifier in slice 1 — for the same reason: an empty or whitespace-only value is exactly as unusable as a missing one, and should be rejected at the same 400 tier rather than surviving to fail as "unknown account" (422) further downstream.

Both `@NotNull`/`@NotBlank`/`@Valid` (whether on a field or on a type parameter) drive the same `@Valid`-triggered `MethodArgumentNotValidException` → inherited-400 path slice 1 already established — a missing/null `lines`, a null element within `lines`, a blank `account_code`, or a missing/null `amount_cents` is rejected before any domain object is built, not after.

A non-integral `amount_cents` (e.g. `9750.9`) must also fail binding rather than silently truncate to `9750` — Jackson's default scalar coercion would otherwise accept it. In Jackson 3 the lever is `MapperBuilder.withCoercionConfig(LogicalType, ...)`, reached through a `JsonMapperBuilderCustomizer` bean (`config.JacksonConfig`); confirmed empirically in task 5.1 rather than assumed.

That first rule turned out to be one instance of a wider defect class, found by QA after implementation: Jackson converts between JSON scalar types whenever it can, so `"amount_cents": "-100"` (a JSON string) was converted to a number and the money posted, and `"account_code": 12345` / `true` were converted to strings and reported as "unknown account". The same leniency was behind slice 1's deferred findings (a numeric `owner_ref` stored as text, `"type": 0` accepted as an enum ordinal). The fix is therefore global, not per-field, in `JacksonConfig`: integer targets reject float and string input; string targets reject integer, float and boolean input; enum targets reject integer input. A client must send the JSON type the API documents, and gets a 400 otherwise — consistent with this project's reject-don't-normalize convention, and with `PROJECT-CONVENTIONS.md`'s rule that money is a signed integer, never a string or decimal. Because it is global, this also changes slice 1's behavior (those inputs were `201`, now `400`); it contradicts nothing in slice 1's spec, which never promised the conversion, and slice 1's contract tests already expect `400` for them.

### Validation split: request shape in the web layer, pure rules in the domain, I/O-dependent rules in the adapter
`JournalEntry`'s compact constructor (pure, no I/O — same style as `Account`) validates:
- `idempotencyKey` shape (1–255 visible ASCII) → `IllegalArgumentException` → 400, same handler tier as slice 1's code/owner_ref rejections.
- Every line's `amount_cents` has an absolute value of at most 10^12 → a new `AmountLimitExceededException` → 422 (see the amount-cap decision below). Checked before the sum, so an over-limit line is reported as such rather than as a sum problem.
- The lines' `amount_cents` sum to exactly zero, computed with `Math.addExact` so an overflow surfaces as an `ArithmeticException` rather than wrapping silently → both a nonzero sum and an overflow are wrapped into a new `UnbalancedJournalEntryException` → 422.

Two rules can't be checked without a database round-trip — whether each `account_code` exists, and whether every referenced account shares one currency — so they can't live in the pure domain constructor. Rather than introduce a domain "use-case" service purely to hold I/O-dependent orchestration (a heavier abstraction than a two-endpoint slice needs), these checks live in the JDBC adapter, immediately before the insert, matching how slice 1's adapter already catches `DuplicateKeyException` and translates it to a domain exception — I/O-shaped failures are the adapter's job, not the domain's. The adapter:
1. Resolves every distinct `account_code` referenced by the entry to its `(id, currency)` in one query; any code not found → `UnknownAccountException` (422). The resolved rows are then handed to a small pure function — `validateSingleCurrency(List<ResolvedAccount>)`, taking already-fetched `(code, id, currency)` rows, no SQL of its own — that throws `MixedCurrencyException` (422) if they span more than one currency. Kept separate from the resolution query specifically so it's unit-testable without a database (see the currency-uniformity decision below).
2. Inserts the `journal_entries` header row in its own `try/catch` scoped to *only* that statement — never the line insert in step 3. That single `INSERT` touches two unique constraints on the *same* table: the named `journal_entries_idempotency_key_uq`, and `entry_id`'s own inline `UNIQUE` (server-generated via `gen_random_uuid()`, so a collision is exceedingly unlikely, but not structurally impossible). Both would surface as the same `DuplicateKeyException` type, so the catch block can't assume which one fired — it reads the actual violated constraint's name from the driver's structured error response, `((PSQLException) e.getMostSpecificCause()).getServerErrorMessage().getConstraint()`, rather than matching on free-text exception message content. Only when that name is `journal_entries_idempotency_key_uq` does it translate to `DuplicateIdempotencyKeyException` (409) — same pattern as `AccountAlreadyExistsException`, just with an added check on *which* constraint fired. A coincidental `entry_id` collision is a different failure (an internal anomaly, not a business rule) and must not be reported as a duplicate idempotency key; any other unique violation — including that one — propagates uncaught, falling through to the generic 500 handler. `entry_lines`' own `UNIQUE (journal_entry_id, line_number)` is irrelevant to this catch block entirely: it's a different table, hit by the separate, unguarded statement in step 3, outside this `try`.

   Referencing `PSQLException` directly requires `pom.xml`'s `org.postgresql:postgresql` dependency to move from `<scope>runtime</scope>` to compile scope (the default) — legitimate for a JDBC adapter to depend on driver-specific types when it needs driver-specific detail, the same way it already depends on Spring Data's `DuplicateKeyException`.
3. Batch-inserts the resolved `entry_lines` rows as a separate, unguarded statement. Its own `UNIQUE (journal_entry_id, line_number)` should be unreachable in normal operation (line numbers are controller-assigned and sequential), so a violation here is a real bug, not a condition to translate — it should surface as a 500, which not catching it here already achieves.
4. Returns the fully-populated `JournalEntry` (server-assigned `entryId`/`createdAt` read back via `RETURNING`).

All four steps run inside one Spring-managed transaction (`@Transactional` on the adapter method — legal there since, unlike the domain package, the adapter already depends on Spring) so the header and lines commit atomically, and so the deferred trigger evaluates at the same commit that steps 2–3 belong to.

New domain exceptions (`domain.journalentry`, unchecked, mirroring `AccountAlreadyExistsException`'s shape): `UnbalancedJournalEntryException`, `UnknownAccountException`, `MixedCurrencyException`, `AmountLimitExceededException`, `DuplicateIdempotencyKeyException`. `ApiExceptionHandler` gains one new handler tier: the first four map to 422, the last to 409 alongside the existing `AccountAlreadyExistsException` handler.

Alternative considered: a domain/application service (e.g. `PostJournalEntry`) holding this orchestration, keeping the adapter a thin `INSERT` wrapper. Rejected for this slice — it's the kind of layer that earns its cost once there's a second write path or the orchestration grows, neither true yet; the adapter is already where slice 1 put comparable logic (duplicate detection).

### Currency-uniformity check is unreachable against a real database today — tested as a pure function instead
`accounts.currency` is `CHECK (currency IN ('BRL'))` (`V1__create_accounts.sql`, confirmed by inspection) — there is no way to make a second-currency account exist, at any layer, including a direct `JdbcTemplate` insert bypassing the domain entirely; the `CHECK` applies regardless of how a row is written. That means the mixed-currency rule can't be exercised end-to-end against Postgres in this slice: it exists now purely because the input spec calls for it as an explicit rule, ahead of multi-currency support arriving in slice 6 — not because deferring it would be structurally expensive. Unlike a schema column, this check is plain Java validation logic; `entry_lines`' upcoming append-only trigger (slice 3) constrains what can be done to *rows already written*, not what validation code can be added later, so there's no cost advantage to writing it now versus in slice 6. It's included now simply because the rule is already specified and cheap to add, and the single-currency system makes it structurally unreachable today either way (see "Decisions recorded for later slices" in proposal.md).

To keep it verifiable anyway, `validateSingleCurrency` (introduced above) is written to take already-resolved rows rather than being inlined into the SQL-resolution step. Task 3.3 tests it directly with a fabricated list of resolved accounts spanning two currency values — no database, no seeding, no dependency on `accounts.currency` ever admitting a second value.

### Per-line amount cap: every line is limited to ±10^12 centavos so balances stay representable
Found by QA after implementation. Two entries of `[+Long.MAX_VALUE, -Long.MAX_VALUE]` each balance, so each passes the sum check and both post — but afterwards that account's `SUM` is twice `Long.MAX_VALUE`, which doesn't fit the `long` that `balanceOf` returns and the response carries. Every balance read for the account then fails with a 500, permanently. The entry-level overflow rule ("overflow is a 422, never a silent wrap") guards one entry's sum, but nothing guarded the *accumulated* per-account sum: the system was accepting input whose consequence it couldn't later represent.

The fix is a per-line cap of 1,000,000,000,000 centavos (10 billion BRL) in absolute value, enforced in `JournalEntry`'s constructor so every construction path is covered, throwing `AmountLimitExceededException` → 422 (a well-formed request that breaks a ledger rule, the same tier as an unbalanced entry). With it, one account would need on the order of 9 million maximum-size lines in the same direction before its balance could overflow a `long` — unreachable in practice. It makes the failure infeasible rather than mathematically excluding it. One consequence: an entry like `[Long.MAX_VALUE, Long.MAX_VALUE, 2]`, previously rejected by the entry-level overflow check, is now rejected by the cap one step earlier (still 422). The `Math.addExact` guard stays as defense in depth, but reaching it needs on the order of 9 million maximum-size lines, so it no longer has a dedicated unit test.

Alternatives considered: widening `balanceOf` / `balance_cents` to `BigInteger` — makes overflow genuinely impossible, but touches the port, controller, response and every test, and puts integers beyond 2^53 into a JSON number that many clients can't read exactly; disproportionate here. Doing nothing — rejected, since the failure mode (an account whose balance is permanently unreadable) is a hard fault, not a cosmetic edge case.

There is deliberately **no database `CHECK` mirroring the cap**, unlike the two-layer pattern used for shape rules (`code`, `idempotency_key`): (a) the cap is a policy limit, not a structural invariant, so changing the number should be a code change rather than a migration; (b) `EntryLinesBalanceTriggerTest` (task 4.1c) deliberately inserts `Long.MAX_VALUE` lines straight into `entry_lines` to prove the trigger reports `LDG01` rather than `22003` on overflow — a `CHECK` would reject those inserts first and leave the trigger's own overflow handling untestable. The trigger stays the backstop for the balance invariant; the cap is enforced in the domain only.

### Exactly one `Idempotency-Key` header
Spring's `@RequestHeader String` silently joins repeated headers into a single comma-separated value (`"k1,k2"`), which is itself a valid visible-ASCII key. So a post carrying two headers succeeded under the combined key, and a later retry with `k1` alone was not recognized as a duplicate — the money posted twice (found by QA). The controller therefore reads the header's values as a list (`@RequestHeader HttpHeaders`) and rejects anything other than exactly one value with `IllegalArgumentException` → 400. That also covers the missing-header case, replacing Spring's built-in missing-header 400 with the same status through this API's own `problem+json` handler. Only separate header lines count as multiple: a single line whose value happens to contain a comma is simply a key containing a comma, which the key format permits.

### Reading balance: reuse `AccountPort` for existence and currency, add one port method for the sum
`GET /accounts/{code}/balance` is served by a new `AccountBalanceController` (`adapter.web.journalentry`, alongside `JournalEntryController` — grouped by capability, not by URL path) that:
1. Calls the existing `AccountPort.findByCode`, throwing the existing `ResourceNotFoundException` on a miss — same 404 path `AccountController` already uses, and the source of the response's `currency` field.
2. Calls a new `JournalEntryPort.balanceOf(String accountCode)`, implemented as `SELECT COALESCE(SUM(amount_cents), 0) FROM entry_lines JOIN accounts ON accounts.id = entry_lines.account_id WHERE accounts.code = ?` — `COALESCE` gives the required zero-for-no-lines behavior for free, and `entry_lines_account_id_idx` (see schema above) keeps the join an index lookup rather than a sequential scan of `entry_lines`.

Alternative considered: a single port method returning something like `Optional<Balance>` so one query serves both existence and sum. Rejected — it would need `accounts` and `entry_lines` joined with an `outer` join to distinguish "no account" from "account, no lines," which is more query complexity for a call site (`AccountBalanceController`) that gets the same answer for free by reusing the lookup `AccountController` already needed for its own 404 case.

### `JournalEntry` / `EntryLine` domain shape
```java
record EntryLine(int lineNumber, String accountCode, long amountCents) {}

record JournalEntry(UUID entryId, String idempotencyKey, List<EntryLine> lines, Instant createdAt) {}
```
`entryId` and `createdAt` are nullable pre-persistence, the same treatment `Account.createdAt` got in slice 1, for the same reason (the database assigns them). `EntryLine`'s own compact constructor validates `lineNumber >= 1` and rejects a null `accountCode` with `IllegalArgumentException`; `accountCode` isn't otherwise checked against `Account`'s code-shape regex, since an unrecognized or malformed (but non-null) `account_code` both end up as "no account with that code" — already `UnknownAccountException` → 422 — so a second, earlier shape check would only change which error is reported, not the outcome. A null `accountCode` is different: nothing downstream can meaningfully report "no account with that code" for a value that isn't a code at all, so it's rejected here instead.

`JournalEntry`'s compact constructor carries the stronger, list-level invariants, deliberately not trusting the web layer to have gotten them right even though `JournalEntryController` is the only caller today:
- A null `idempotencyKey` → `IllegalArgumentException`, checked *before* the visible-ASCII regex match — matching a `Pattern` against a null `String` throws `NullPointerException`, which (as below) isn't what any handler tier expects here.
- A null `lines` list, or a list containing a null element, → `IllegalArgumentException`. (This diverges from `Account`'s `Objects.requireNonNull`-throws-`NullPointerException` style for its own required fields — deliberately: `ApiExceptionHandler` has no handler for `NullPointerException`, so an NPE here would fall through to the generic 500 handler instead of 400. `IllegalArgumentException` is used uniformly for every rejection in this constructor so all of them land on the existing 400 tier.)
- The list is defensively copied (`List.copyOf(lines)`) after validation, so the constructed `JournalEntry` can't be mutated by a caller holding a reference to the original list.
- The lines' `lineNumber`s must be exactly the sequence `1..N` in order (covering duplicates, gaps, and out-of-order lines in one check) → `IllegalArgumentException`. `line_number` itself isn't client input — `JournalEntryController` assigns it from each line's position in the submitted array before constructing `EntryLine`s — but the domain enforces the invariant itself rather than trusting the one caller that currently exists to have assigned it correctly, consistent with this project's two-layer validation philosophy applied one level further in.

None of these null-guard paths are reachable through the current HTTP flow — the DTO validation above already rejects a null `Idempotency-Key` header, a null `lines`, a null element, and a null `account_code` before a `JournalEntry` or `EntryLine` is ever constructed. They exist for the same reason `Account`'s own defensive checks did in slice 1: any caller other than today's one web controller (an adapter test, a future second caller) shouldn't be able to construct an invalid domain object at all.

## Risks / Trade-offs

- [A zero-line or one-line entry currently succeeds — a zero-line entry trivially sums to zero, a one-line entry can only "balance" via `amount_cents = 0`] → deliberately deferred to slice 3 per the input spec's own scoping ("degenerate guards"), not an oversight. Flagged here so it isn't mistaken for one when slice 3 starts.
- [The deferred-trigger backstop fires at transaction commit, not inside any single `JdbcTemplate` call, so it's unconfirmed which exception type Spring's transaction manager surfaces it as] → not a blocker: the existing catch-all `Exception` handler in `ApiExceptionHandler` already covers any unanticipated exception type with a generic 500 + server-side log, so behavior is correct regardless of the exact type. Pinning the exact type down is exactly what the adapter-level trigger tests (tasks.md) exist to do, consistent with this project's practice of verifying JDBC/Spring exception behavior empirically rather than assuming it.
- [`FOR EACH ROW` re-sums the whole entry once per inserted line, so an N-line entry does N redundant `SUM` scans of its own (small) row set at commit] → accepted; `FOR EACH ROW` is the only legal form for a deferred constraint trigger in Postgres, and `entry_lines` per entry is small.
- [The idempotency-key `CHECK` constraint added here goes beyond the input spec's literal schema] → accepted as a minor, convention-consistent addition (see Decisions above); it changes no observable behavior, since the domain layer already rejects the same malformed keys first.
- [Referenced-account resolution and the header insert are two separate statements before the batch line insert, all inside one transaction] → accepted; this is the same shape as slice 1's single-adapter-method pattern, just with an extra read before the write, and the transaction boundary keeps it atomic.

- [Control characters (e.g. DEL `0x7F`, `0x01`) in the `Idempotency-Key` header are rejected by Tomcat itself with an HTML 400 before the request reaches the application, so that one response is `text/html`, not `problem+json`] → deliberately not fixed. It is container-level behavior (fixing it means custom connector error handling), no real client sends such headers, and the outcome itself is correct — the key is not accepted and nothing is posted. Recorded here rather than silently dropped, alongside the Unicode-whitespace finding in `accounts-and-skeleton`.

## Migration Plan

New capability, no existing `journal_entries`/`entry_lines` data. Deploying this change applies Flyway `V2__create_journal_entries.sql`; rollback is dropping the two (currently nonexistent) tables and the trigger function, in FK order (`entry_lines` before `journal_entries`). `accounts` is unaffected — no migration touches it.
