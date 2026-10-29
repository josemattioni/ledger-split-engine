## Context

See proposal.md for the defects. The relevant current state:

- `config.JacksonConfig` makes scalar typing strict through `JsonMapperBuilderCustomizer` + `withCoercionConfig`. Its Enum rule blocks only the JSON Integer shape. Jackson's enum deserializer still resolves a *string* that parses as an integer by ordinal. Repeated properties are not detected. Both behaviors were confirmed against jackson-databind 3.1.5 (the version on the classpath) with a scratch program using this exact coercion config: `{"type":"0"}` → `ASSET`, and `{"amount_cents":0,"amount_cents":-250}` → `-250`.
- `JournalEntry`'s compact constructor holds every rule that needs no stored data, in a fixed order: key/structure (400) → fewer than two lines → zero amount → repeated account → amount cap → balance (422). `EntryLine` checks only `lineNumber >= 1` and non-null `accountCode`.
- `Account` holds the account-code pattern and length (100). `accounts.code` has the same anchored pattern as a CHECK. Nothing checks a journal line's code shape.
- `ApiExceptionHandler` maps every `IllegalArgumentException` to 400 with `ex.getMessage()`. The domain constructors (`Account`, `EntryLine`, `JournalEntry`) throw `IllegalArgumentException` for structural defects. The same constructors also run on stored rows (`JdbcAccountAdapter.mapRow`, `JdbcJournalEntryAdapter.loadByKey`).
- The deferred `trg_entry_lines_balance` re-sums the whole entry once per line at commit, so its cost grows with the square of the line count. `resolveAccounts` binds one parameter per distinct code.

## Goals / Non-Goals

**Goals:**
- Every rule that needs no stored data stays in `JournalEntry`, in one documented order, so it is reported whatever the key (the slice 4 evaluation order).
- One Java source for the account-code shape, used for registration and for journal lines.
- A 400 means "the request was wrong"; nothing else produces one.

**Non-Goals:**
- No migration and no new database check (see Decision 2).
- No change to registration's handling of a malformed `code` (still 400).
- No change to the precedence between existing rules.

## Decisions

### 1. Both Jackson features live in `JacksonConfig`
Add `.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)` and `.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)` to the existing customizer. Both were verified in the same scratch program. With them, `"type":"0"` fails with "not one of the values accepted", `"ASSET"` still binds, and a repeated property fails with "Duplicate Object property". Spring turns both into `HttpMessageNotReadableException` → 400 with the default type, the same path as every other malformed body.

- **Alternative rejected: `application.yaml` properties.** Strictness is already in one class, and its Javadoc is the place a reader looks. Splitting it across YAML and Java would leave two places to check.
- **Cost.** Duplicate detection keeps a set of property names per JSON object. That is negligible for bodies capped at 100 lines.
- `@WebMvcTest` slices only see this when they `@Import(JacksonConfig.class)`, as the journal-entry controller test already does. The account controller test needs the import if it doesn't have it.

### 2. Line limit: 100, in the domain, no database check
`JournalEntry.MAX_LINES = 100`, checked right after `MIN_LINES`, throwing `LineLimitExceededException(lineCount)` → 422 `line-limit-exceeded`. The detail is "journal entry has N lines; at most 100 are permitted".

- **Why 100.** A capture split posts 3 lines. A multi-seller cart posts about two per seller plus fee and commission, so 100 covers about 45 sellers in one entry. At 100 lines the database work per entry is a 100-parameter lookup and at most 10,000 row visits in the quadratic balance trigger, so the cap also settles that cost. The value is one constant.
- **No database check.** Like the amount cap, this is a policy value, not an invariant of the data. A row-count limit would need yet another deferred trigger.
- **Alternative rejected: `@Size(max = 100)` on the request DTO.** That would make it a 400 with the default type. The decision is a 422 with its own type, like every other ledger rule, and the DTO deliberately carries no `@Size` (it would also turn `{"lines":[]}` into a 400).

### 3. Account-code shape: one validator in `domain.account`, enforced per line in `JournalEntry`
A new final class `domain.account.AccountCode` holds `MAX_LENGTH`, the pattern, `isWellFormed(String)` and the human-readable shape description. `Account` uses it, and its error message stays byte-for-byte the same. `JournalEntry` checks every line and throws `InvalidAccountCodeException(lineNumber, code)` → 422 `invalid-account-code`, with the detail `line N: account_code "X" must be lowercase snake_case, optionally followed by ":identifier" (max 100 characters) — e.g. "seller_payable:123"`.

- **Why in `JournalEntry` and not `EntryLine`.** `EntryLine` is constructed in the controller's mapping loop and from stored rows. A check there would fire before `JournalEntry`'s own ordering (for example, before the idempotency-key shape check) and re-run on every stored line. In `JournalEntry` it is one more step in the single ordered sequence.
- **Why 422 here when registration answers 400.** At registration the code is the identifier being created, so a bad one is a malformed field. On a journal line the JSON is well-formed, and the code simply names no account that could exist. That makes it a ledger rule with its own type, next to `unknown-account`. The README states both cases so the asymmetry is documented, not accidental.
- **Exact comparison.** No trimming or case-folding, in line with the project's reject-don't-normalize rule. A padded code is rejected, not cleaned up.

### 4. Rule order in `JournalEntry`
structure/key (400) → fewer than two lines → **more than 100 lines** → **account-code format** → zero amount → repeated account → amount cap → balance.

Counts come first because they are cheap and bound the work of everything after them. Next comes what each line names, then the amounts. Existing rules keep their relative order, so every existing precedence test still holds. Consequences: a 101-line entry is reported as `line-limit-exceeded` whatever else is wrong with its lines, and a malformed code on an entry that also has a zero amount reports `invalid-account-code`.

### 5. 400 only for errors from building the request
A new `adapter.web.InvalidRequestException` (400, its message as the detail) has a static helper that runs a request-to-domain construction and rethrows an `IllegalArgumentException` from it as an `InvalidRequestException`. `AccountController` wraps `new Account(...)`. `JournalEntryController` wraps the `EntryLine`/`JournalEntry` construction and throws `InvalidRequestException` directly for the header-count check. `ApiExceptionHandler` replaces its `IllegalArgumentException` handler with one for `InvalidRequestException`. Any other `IllegalArgumentException` then reaches the catch-all: 500, generic detail, ERROR log.

The domain rule exceptions (422) extend `RuntimeException`, not `IllegalArgumentException`, so they pass through the wrapper untouched. The two new exceptions follow the same rule.

- **Alternative rejected: a domain `ValidationException` type mapped to 400.** The same constructors run on stored rows, where a failure is a server defect and must be a 500. The exception type can't tell a request from a stored row; the call site can.
- **Alternative rejected: try/catch in each controller.** It is the same logic. The helper keeps it in one line per call site.

### 6. Behavior-neutral cleanups
- `AbstractIntegrationTest` gains the fixture helpers the integration tests each re-declare: `uniqueCode(prefix)`, `uniqueIdempotencyKey()`, and an account-registration helper. The subclasses drop their copies. Helpers that differ in substance (raw-SQL header inserts used by the trigger tests) stay where they are.
- `JournalEntry`'s key pattern is built from `MAX_IDEMPOTENCY_KEY_LENGTH`, so the message and the check can't drift.
- `JdbcJournalEntryAdapter.balanceOf` returns the `COALESCE`'d value directly. The Java null branch was unreachable.
- `IdempotentInsertConcurrencyTest.awaitLockWait` polls through one owner connection instead of a new physical connection per poll.
- `DegenerateEntryBackstopsTest`'s assertion message drops the retired 409. The matching comment in `V3__append_only_ledger.sql` stays: editing an applied migration changes its Flyway checksum and breaks every existing database.

### 7. The QA-owned contract suite
Checked against the current contract sources: `IdempotentReplayContractTest.accountCodeSpellingNotNormalized` accepts only 400 or 422 `idempotency-key-reused` for a used key plus an upper-cased or padded code. Its `upper-cased`, `leading space` and `trailing space` cases will now see 422 `invalid-account-code` and fail. The `empty` and `whitespace-only` cases stay 400 (binding). These still pass by design: `inexactAccountCodeRejected` (accepts 400 or 422), `duplicateAmountKey` (accepts 201 or 400/422; now 400), `twoHundredDistinctLines` and `thousandLines` (accept 201 or 4xx; now 422 `line-limit-exceeded`), and `ordinalTypeRejected` (bare `0`, still 400). The apply step re-measures `./mvnw verify` against the current baseline rather than trusting this prediction, and edits no contract test.

## Risks / Trade-offs

- [A client that posts more than 100 lines is now rejected] → Documented in the README with the rationale; the limit is one constant.
- [The account-code shape lives in Java and in the `accounts.code` CHECK] → One Java class now serves both uses. The SQL CHECK stays the anchored mirror, as the schema conventions require.
- [Registration answers 400 and journal lines answer 422 for the same malformed code] → A deliberate distinction (Decision 3), stated in the README and the specs.
- [A future controller that builds domain objects without the wrapper turns a client mistake into a 500] → Loud rather than silent, and caught by that endpoint's 400 tests. `PROJECT-CONVENTIONS.md` states the rule.
- [Three contract-suite cases go red] → Listed above and in the apply report; QA updates them from the new spec.

## Migration Plan

Code-only: no schema, grant or data change. Stored entries already satisfy both new rules (every stored code passed the `accounts.code` CHECK, and no entry today has more than 100 lines), so replays of existing entries are unaffected. Rollback is reverting the code.
