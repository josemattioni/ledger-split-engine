## Why

The ledger is append-only, so a mistaken entry can be neither edited nor deleted. A reversal is the correction mechanism: a new entry that exactly negates the original and is linked to it, so the history keeps both the mistake and its correction. After this slice any posted entry can be reversed once, safely under retries and concurrency.

## What Changes

- **New endpoint.** `POST /api/v1/journal-entries/{entry_id}/reversal`, with a required `Idempotency-Key` header and no body. `entry_id` is the original's public UUID. It answers `201 Created` with the reversal entry, or `201` plus `Idempotent-Replayed: true` for a retry with the same key.
- **Full reversal only, built by the server.** The reversal has the original's lines — same `line_number`, same `account_code` — with each amount negated. The client never sends lines. A partial correction is an ordinary new entry, not a reversal.
- **At most once; never a reversal of a reversal.** A second reversal of an entry is `422 entry-already-reversed` (the detail names the original). Reversing a reversal is `422 cannot-reverse-a-reversal`; to undo a reversal, post the original movement again as an ordinary entry.
- **A reversal is an ordinary entry otherwise.** It has its own `entry_id`, `created_at` and idempotency key, and it counts in balances, so after a reversal every affected account is back to its balance from before the original.
- **New response field.** Every entry representation gains `reverses_entry_id` — the original's public `entry_id` for a reversal, `null` for an ordinary entry. This is additive on `POST /journal-entries`.
- **Errors.** `400` for a malformed path UUID, a missing or invalid `Idempotency-Key`, or a request body. (A control character in that header, such as DEL or `0x01`, never reaches the application: Tomcat rejects the request first, with its own 400 page rather than problem+json. The specs now say so; see design.md Decision 10.) `404` (problem+json) for an unknown `entry_id`. `422` `cannot-reverse-a-reversal`, `entry-already-reversed`, and `idempotency-key-reused` (including a key that was used for an ordinary entry).
- **Evaluation order.** Path and header (400) → load the target (404) → build the reversal in the domain, refusing a reversal as target (422) → the slice 4 insert-or-find on the key (replay or reuse) → the at-most-once constraint (422). A retry of a successful reversal therefore replays and never reports `entry-already-reversed`.
- **Idempotency covers the new field.** `sameRequestAs` now compares the lines *and* the entry being reversed, as slice 4's design required of any new request field. The same key with a different target, or with an ordinary entry, is `422 idempotency-key-reused`.
- **Schema V4.** `journal_entries.reverses_entry_id` (nullable, references `journal_entries(id)`), `UNIQUE` on it (at most once), and a deferred trigger that checks at commit that a reversal's lines are the exact negation of its original's (`LDG04`) and that its original is not itself a reversal (`LDG05`, which also rules out a reversal that references itself and a cyclic link). No grant change; no existing row is rewritten.
- **Conventions and docs.** `PROJECT-CONVENTIONS.md` gains the reversal rule and "every request field that determines what is stored takes part in `sameRequestAs`". `README.md` documents the endpoint, the field and the new problem types.
- **BREAKING (QA-owned contract suite):** two contract tests assert that no key ending in `_id` other than `entry_id` appears in an entry response, and will fail on `reverses_entry_id`. This change does not edit contract tests; they are left for QA to update from the new spec. See design.md.

**Out of scope:** partial reversal; a reason or description on a reversal; `GET /journal-entries/{entry_id}` (cheap now, but not required); slice 6 (account field lock, as-of balance — a balance as of a moment between an entry and its reversal shows the original's effect, which is the point of keeping history).

## Capabilities

### New Capabilities
- `journal-entry-reversal`: reversing a posted entry in full — the endpoint, what a reversal is, the at-most-once and no-reversal-of-a-reversal rules, the 400/404/422 outcomes, replay and reuse of the idempotency key, evaluation order, and concurrent reversals.

### Modified Capabilities
- `journal-entries`: the entry representation gains `reverses_entry_id`; "the same request" for idempotency now includes the entry being reversed; the problem-type table gains `cannot-reverse-a-reversal` and `entry-already-reversed`; the identity requirement names `reverses_entry_id` as the one other public identifier; the storage-backstop requirement covers the reversal safeguards; "Journal entry errors are reported as problem+json" gains its one exception, a request the HTTP server rejects before the application sees it (a control character in the `Idempotency-Key` header), which gets the server's own 400 page.

## Impact

- **Migration:** `V4__entry_reversal.sql` (additive: one nullable column, two constraints, one trigger). Flyway applies it as the owner on the next start; no volume reset, no data change.
- **Code:** `domain.journalentry` (`JournalEntry` gains `reversesEntryId`, `reversalOf`, `isReversal`; new `IdempotencyKey`, `CannotReverseAReversalException`, `EntryAlreadyReversedException`, `JournalEntryNotFoundException`; `JournalEntryPort.findByEntryId`); new `application.journalentry.ReverseJournalEntry`; `JdbcJournalEntryAdapter` (link on insert, one load path for key and `entry_id`, the at-most-once catch); `JournalEntryController` (the new endpoint), `JournalEntryResponse`, `ApiExceptionHandler`, `ProblemTypes`.
- **Tests:** new domain, use-case (fake port), DB-level (real commits, both trigger codes, both constraints), adapter, deterministic two-connection concurrency, controller, end-to-end and 20-round race tests; existing tests that assert the full response shape gain the field.
- **Docs:** `README.md`, `PROJECT-CONVENTIONS.md`.
- **QA-owned, not edited:** `IdempotentReplayContractTest.replayBodyShape` and `JournalEntryContractTest.entryIdServerAssigned`. No contract test covers reversal yet.
- **QA-owned, to relax:** the DEL and `0x01` cases of `keyWithInvalidCharacterRejected` in `JournalEntryContractTest` and in the reversal contract suite assert problem+json for a request Tomcat rejects before the application. Under the carve-out they should expect the server's own 400 (status 400, nothing created). The space, tab and non-ASCII cases are unaffected: the application receives those.
