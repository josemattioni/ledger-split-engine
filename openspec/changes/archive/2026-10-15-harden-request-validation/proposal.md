## Why

A review of slices 1–4 found requests the API accepts or classifies wrongly. A JSON body with a repeated property silently keeps the last value, so `"amount_cents":0,"amount_cents":-250` posts -250 with a 201. A quoted enum index (`"type":"0"`) registers an `ASSET`. An entry can have any number of lines, and at 65,536 distinct codes the account lookup exceeds Postgres's bind-parameter limit and returns a 500. A line's `account_code` is never shape-checked, so under a used key a malformed code is reported as `idempotency-key-reused`, although the spec says rules that need no stored data come first. Separately, every `IllegalArgumentException`, from any layer, becomes a 400, so an internal fault can be reported as the client's error and is not logged. These should be fixed before slice 5 adds new request fields on top of the same validation path.

## What Changes

- **Repeated JSON properties are rejected.** A property that appears twice in one JSON object is a malformed request: 400 with the default problem type, on every endpoint. Nothing is written.
- **Quoted enum indexes are rejected.** An enum value must be one of its names. A string holding a number (`"type":"0"`) is a 400, the same as the bare number `0` already is.
- **An entry has at most 100 lines.** More than 100 lines is a 422 with the new type `urn:ledger-split-engine:problem:line-limit-exceeded`. It is checked in the domain, with the other rules that need no stored data, so it is reported whatever the key and before any database work.
- **Each line's `account_code` must be well-formed.** It must have the shape account registration requires (lowercase snake_case, optional `:identifier`, at most 100 characters). Otherwise the post is a 422 with the new type `urn:ledger-split-engine:problem:invalid-account-code`, whose detail names the line and states the rule. It is checked in the domain before the key lookup, so a malformed code under a used key reports `invalid-account-code`, not `idempotency-key-reused`. `unknown-account` is now only for well-formed codes that match no account.
- **400 is only for requests the client got wrong.** An `IllegalArgumentException` raised while the controllers turn the request into domain objects is still a 400 with its message. Any other `IllegalArgumentException` falls through to the generic 500 with a logged error, like every other unexpected failure.
- **Behavior-neutral cleanups.** Shared integration-test helpers move into `AbstractIntegrationTest`. The idempotency-key pattern is built from its length constant. A dead null branch in `balanceOf` goes away. The lock-wait poll in the concurrency test reuses one connection. A test message that still mentions the retired 409 is corrected.
- **BREAKING (clients):** bodies with repeated properties, string-encoded enum indexes, entries over 100 lines, and lines with malformed account codes were accepted, or got a different error, before. Each is now rejected as described.
- **BREAKING (QA-owned contract suite):** `IdempotentReplayContractTest.accountCodeSpellingNotNormalized` accepts only 400 or 422 `idempotency-key-reused` for an upper-cased or padded code under a used key. The new rule answers 422 `invalid-account-code`, so its `upper-cased`, `leading space` and `trailing space` cases will fail. This change does not edit contract tests; they are left for QA to update from the new spec.

**Out of scope:** modelling the request payload as its own type so `sameRequestAs` covers new fields automatically (slice 5, where the first new request field arrives); moving account registration behind an `application` use case; the executable bit on `mvnw`, which is set when the project is first committed, not in code.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `journal-entries`: new requirements for the 100-line limit and well-formed line account codes; the malformed-request requirement gains repeated properties; the problem-type table gains `line-limit-exceeded` and `invalid-account-code`; the evaluation-order requirement lists both new rules among those checked before the key; "Every line must reference an existing account" applies to well-formed codes; the problem+json requirement states that an internal failure is never reported as a client error.
- `account-registration`: the type-validation requirement rejects a string-encoded index; the exact-field-names requirement rejects a field repeated within the request.

## Impact

- **No migration.** Neither limit gets a database check: the line limit is a policy value like the amount cap, and every stored account code already satisfies the `accounts.code` CHECK.
- **Code:** `config.JacksonConfig` (two parser/enum features); `domain.account` (a shared account-code validator used by `Account`); `domain.journalentry` (`JournalEntry` gains the line limit and the account-code check; new `LineLimitExceededException` and `InvalidAccountCodeException`); `adapter.web` (new `InvalidRequestException` for 400s; `ApiExceptionHandler` drops the blanket `IllegalArgumentException` handler and adds two 422 handlers; `ProblemTypes` gains two types; both controllers wrap request-to-domain construction); `JdbcJournalEntryAdapter.balanceOf`.
- **Tests:** new domain, controller and end-to-end tests for each rule (one defect each, asserting the problem type), Jackson tests for both endpoints, and a test that an `IllegalArgumentException` from the use case is a 500. Integration tests are refactored onto shared helpers.
- **Docs:** `README.md` (the two limits, the new problem types, repeated properties); `PROJECT-CONVENTIONS.md` (repeated properties rejected; what a 400 means).
- **QA-owned, not edited:** `IdempotentReplayContractTest.accountCodeSpellingNotNormalized` (three cases).
