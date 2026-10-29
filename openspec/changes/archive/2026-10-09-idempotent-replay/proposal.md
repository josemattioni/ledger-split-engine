## Why

Retrying a POST must be safe. Today a repeated `Idempotency-Key` gets a 409, which neither returns the entry that was actually stored nor tells "you already did exactly this" apart from "you reused a key for a different request". A client that lost the first response (timeout, dropped connection) cannot recover it. After this slice a retry gets the original response back, and reusing a key for a different request is a clear 422.

## What Changes

- **Replay.** Same key, same payload → `201 Created` with the original body (same `entry_id`, `idempotency_key`, `created_at`, lines in `line_number` order) and the response header `Idempotent-Replayed: true`. Nothing is written. The header appears on replays only; a first post is unchanged.
- **Reuse is rejected.** Same key, different payload → `422` `application/problem+json` with the new type `urn:ledger-split-engine:problem:idempotency-key-reused`; the detail names the key and does not echo the stored payload. "Same payload" means the same lines after `line_number` assignment: same count, same `account_code` and `amount_cents` at every `line_number`. The comparison is order-sensitive.
- **The 409 is retired.** The "Idempotency keys are unique" requirement is rewritten (MODIFIED); `DuplicateIdempotencyKeyException` and its 409 handler are removed. Posting a journal entry is never answered with 409 again.
- **Keys never expire.** A key lives as long as its entry. Only successful posts consume a key: a request that fails stores nothing, so the same key can be used again.
- **Evaluation order is now specified.** (1) Binding and `JournalEntry` construction (400/422: shape, degenerate, cap, balance), rejected regardless of the key; (2) the idempotency key — insert-or-find the header; (3) only for a new key, account resolution and the currency check, then the lines. A retry is therefore answered from what is stored, and a reused key that also names an unknown account reports `idempotency-key-reused`.
- **Mechanism.** The header insert becomes `INSERT … ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING RETURNING …`. A unique violation would abort the transaction and make the stored entry unreadable; `DO NOTHING` keeps it usable, needs no `UPDATE` privilege and takes no row lock (`ledger_app` can do neither). A conflict loads the stored entry in the same transaction. A concurrent second request with the same key waits on the unique index until the first commits (then it replays or is rejected) or aborts (then its own insert proceeds). There is no "in progress" state.
- **Port and application layer.** `JournalEntryPort.post` becomes `insertOrFind(JournalEntry)` returning a sealed `PostOutcome` (`Created` / `AlreadyExists`), pure Java in `domain.journalentry`. A new `application` package holds the `PostJournalEntry` use case, which decides: `Created` → created; `AlreadyExists` with identical lines → replayed; otherwise `IdempotencyKeyReusedException` (domain, unchecked, 422). `JournalEntry.sameRequestAs` is a pure domain comparison. The adapter reports state; it does not decide. Controllers call use cases for writes and ports directly for reads, so the balance `GET` is unchanged.
- **Conventions and docs.** `PROJECT-CONVENTIONS.md` gains the write-path rule and the idempotency contract; `README.md` documents replay and reuse and tells clients to use a natural business key (e.g. the sale id), not a fresh UUID per attempt.
- **BREAKING (clients):** a repeated `Idempotency-Key` no longer returns 409. An identical retry returns 201 with `Idempotent-Replayed: true`; a different request returns 422. Posting a journal entry no longer produces any 409.
- **BREAKING (QA-owned contract suite):** nine contract-test invocations assert the old 409 behaviour and will fail; they are left for QA to update from the new spec. This change does not edit contract tests. See design.md.

**Out of scope:** `GET /journal-entries/{entry_id}` (the replay already returns the body); moving account resolution and the currency check out of the adapter into the use case; key expiry; slice 5 (reversal); slice 6 (account field lock, as-of balance).

## Capabilities

### New Capabilities
(none — idempotency requirements already live in `journal-entries`, and this slice extends them rather than introducing a separate capability)

### Modified Capabilities
- `journal-entries`: "Idempotency keys are unique" is rewritten from "reject with 409" to "replay an identical retry"; new requirements cover reuse for a different request (422), only successful posts consuming a key, concurrent posts producing one entry, and the evaluation order; the problem-type table gains `idempotency-key-reused`; the problem+json and storage-backstop requirements drop their references to a 409 on a repeated key.

## Impact

- **No migration.** The named constraint `journal_entries_idempotency_key_uq` already exists; no schema or grant changes.
- **Code:** `domain.journalentry` (`PostOutcome`, `IdempotencyKeyReusedException`, `JournalEntry.sameRequestAs`, port method renamed; `DuplicateIdempotencyKeyException` removed); `JdbcJournalEntryAdapter` (insert-or-find, header before account resolution, load-by-key); new `application.journalentry` (`PostJournalEntry`, `PostJournalEntryResult`); `JournalEntryController` (calls the use case, adds the replay header); `ApiExceptionHandler` and `ProblemTypes` (one new 422, the 409 handler removed).
- **Tests:** new domain, use-case (fake port, no Spring), adapter (real commits, including a deterministic two-connection concurrency test and a 20-round race), controller and end-to-end tests; existing tests that asserted 409 or called `post` are rewritten. Negative tests keep the one-defect rule; tests that necessarily break it are named `precedence_…`.
- **Docs:** `README.md`, `PROJECT-CONVENTIONS.md`.
- **QA-owned, not edited:** `JournalEntryContractTest` (`reusedKeyDifferentLines`, `identicalRepeatConflicts`, `concurrentSameKey`), `JournalEntryGuardsContractTest` (`nonRuleErrorsKeepDefaultType` for the "409 reused Idempotency-Key" case), `LedgerAppendOnlyContractTest` (`postedEntryCannotBeChanged` × 4, `correctionIsANewEntry`).
