## Context

Slice 2 made `Idempotency-Key` required and unique: `journal_entries` carries the named constraint `journal_entries_idempotency_key_uq`, and `JdbcJournalEntryAdapter.insertHeader` turns a `DuplicateKeyException` on that constraint into `DuplicateIdempotencyKeyException` → 409. Slice 3 made `ledger_app` (the application role) `SELECT`/`INSERT` only, so it cannot `UPDATE`, `DELETE` or take row locks (`SELECT … FOR UPDATE` needs `UPDATE`). Today the adapter validates and writes in one `@Transactional` method:

```
resolve accounts → unknown? 422 → mixed currency? 422 → insert header (409 on duplicate key) → insert lines
```

and the controller builds a `JournalEntry` and calls `JournalEntryPort.post` directly. There is no `application` package: write logic that needs a decision (here, "is this a retry or a reuse?") has no home other than the controller or the adapter, and neither should make it. See proposal.md for motivation and the specs for requirements.

## Goals / Non-Goals

**Goals:**
- A retry is answered from what is stored; reuse is a clear 422; neither writes anything.
- Correct under concurrency without any lock `ledger_app` lacks and without an "in progress" state.
- Introduce the `application` layer with the smallest useful shape, so the decision lives in one testable place.
- Keep every task boundary on a green build (Decision 7).

**Non-Goals:**
- Moving account resolution and the currency check out of the adapter into the use case. Natural now, but the adapter keeps them (they are I/O-dependent rules and it already owns them).
- A `GET /journal-entries/{entry_id}`, key expiry, or any change to the balance `GET` (queries keep calling ports directly).
- Any schema, grant or migration change.

## Decisions

### 1. The port reports state; the use case decides
`JournalEntryPort.post` is replaced by `PostOutcome insertOrFind(JournalEntry)`, where `PostOutcome` is a sealed interface in `domain.journalentry` with two record cases: `Created(JournalEntry entry)` and `AlreadyExists(JournalEntry stored)`. Both are pure Java. The adapter says what happened ("I inserted this" / "this key already has that entry"); it does not compare payloads or choose a status. `balanceOf` is unchanged.

- **Alternative rejected: the adapter compares and throws.** It would put the replay-vs-reuse rule in persistence code and make it untestable without a database. A sealed outcome keeps the rule in plain Java.
- **Alternative rejected: catch `DuplicateKeyException` and re-query.** After a unique violation Postgres aborts the transaction ("current transaction is aborted, commands ignored until end of transaction block" — observed on 16), so the stored entry could not be read in the same transaction. A second transaction would reopen a race window.

### 2. Insert-or-find with `ON CONFLICT DO NOTHING`, in one transaction
The header insert becomes
```sql
INSERT INTO journal_entries (idempotency_key) VALUES (?)
ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING
RETURNING id, entry_id, created_at
```
- **Row returned → we own the key.** Resolve accounts, insert the lines, return `Created`.
- **No row → the key exists.** Load the stored entry in the same transaction (header by key; lines `ORDER BY line_number` joined to `accounts` for the codes) and return `AlreadyExists`.
- **Probed on Postgres 16 as `ledger_app`** (rolled-back transactions only): the first insert returns the row; an identical second insert returns zero rows and the transaction stays usable; with another session holding an uncommitted header for the same key, the insert blocked until that session rolled back and then returned a row. `ON CONFLICT … DO NOTHING` needs no `UPDATE` privilege and takes no row lock, which is why it works under the slice 3 grants.
- **Concurrency falls out of the unique index.** A second request with the same key waits on the first's uncommitted index entry. If the first commits, the second's insert yields no row and its next statement sees the committed entry (below). If the first aborts, the second's insert proceeds. There is no "in progress" state and no lock beyond the index.
- **Relies on `READ COMMITTED`, pinned in code:** `insertOrFind` is declared `@Transactional(isolation = Isolation.READ_COMMITTED)` instead of inheriting the database default. The load that follows a no-row insert is a new statement with a new snapshot, so it sees the other transaction's commit. Under `REPEATABLE READ` (or `SERIALIZABLE`) that does not hold: a conflict with a row committed after the transaction's snapshot raises `40001` ("could not serialize access due to concurrent update" — observed on 16 in a scratch database), which would be a 500 on a valid retry.
- **Use `query`, not `queryForObject`:** `DO NOTHING … RETURNING` legitimately returns zero rows, and `queryForObject` throws `EmptyResultDataAccessException` on that.
- **"Conflict but nothing found" cannot happen** (entries are append-only and a waiting insert only gives up when the other transaction committed). If it ever does, the adapter throws `IllegalStateException` → 500 rather than pretending the post succeeded.
- **Identity gaps.** `ON CONFLICT` consumes an identity value before it detects the conflict, and rollbacks consume them too, so `journal_entries.id` has gaps. Ids are internal and never exposed; harmless.
- **The slice 3 rule still holds.** The `DuplicateKeyException` catch is removed; the only conflict handled is the named constraint. A collision on `entry_id`'s unique index, or any other unique violation (e.g. `entry_lines_entry_account_uq`), still raises and surfaces as a 500, never a 409.

### 3. Evaluation order: header before account resolution
The adapter's order changes from *resolve → validate → insert header → lines* to *insert-or-find header → (only if created) resolve accounts → validate currency → lines*. `@Transactional` stays on the adapter method, so insert-or-find plus the lines is one transaction.

- If a new key's request names an unknown account (or mixes currencies), the exception propagates out of the `@Transactional` method and the header insert rolls back with it: no orphaned header, and the same key can be used again. A test pins this.
- The full request order is: binding (400) → `JournalEntry` construction (400/422: shape, degenerate, cap, balance) → key insert-or-find → accounts and currency → lines. An invalid request is rejected regardless of its key; a retry is answered from the store without touching accounts; a reused key that also names an unknown account reports `idempotency-key-reused`.
- Holding the unconfirmed header while resolving accounts keeps a concurrent same-key request waiting a few milliseconds longer. Accepted.

### 4. "Same request" is the lines, compared in the domain
`JournalEntry.sameRequestAs(JournalEntry other)` returns `lines.equals(other.lines)`. `EntryLine(lineNumber, accountCode, amountCents)` is a record, so equality is value equality: same count, and the same `account_code` and `amount_cents` at every `line_number`. It is order-sensitive because `line_number` comes from position. It deliberately ignores `idempotencyKey` (equal by construction), `entryId` and `createdAt` (server-assigned).

- **Runs after the adapter's transaction has committed.** Safe because stored entries are immutable (slice 3): the stored lines cannot change between the load and the comparison.
- **Future request fields.** Slice 5's reversal will add a field to the request (the entry being reversed). Any field that determines what is stored must join `sameRequestAs` and be persisted, or a retry with a different value would replay as if identical. Recorded here so slice 5 designs for it.
- **Stored entries are rebuilt through the validating constructor** (`new JournalEntry(...)`), so a stored entry is re-checked against today's rules on every replay. Today every stored entry satisfies them. If a rule is ever tightened (a lower amount cap, say), replaying an older entry that predates it would fail; such a change would have to grandfather existing entries. Not a problem now; noted so it is a decision, not a surprise.

### 5. A real `application` layer, minimal
New package `application.journalentry` (matching the package-per-capability layout), allowed to use Spring; `domain` stays free of it.
- `PostJournalEntry` — a `@Service` with `PostJournalEntryResult execute(JournalEntry request)`; no `@Transactional` (the adapter owns the transaction). `Created` → `Created`; `AlreadyExists(stored)` with `stored.sameRequestAs(request)` → `Replayed(stored)`; otherwise throw `IdempotencyKeyReusedException`.
- `PostJournalEntryResult` — a sealed interface with `Created(JournalEntry)` and `Replayed(JournalEntry)`. A separate type from `PostOutcome`: the outcome is what the store observed, the result is what the use case decided.
- `JournalEntryController.post` calls the use case instead of the port. `Created` → `201`; `Replayed` → `201` plus `Idempotent-Replayed: true`, with the body built from the stored entry through the existing `JournalEntryResponse.from`. The balance controller is untouched.
- **Replayed bodies are identical to the original.** `created_at` is read back from the database with microsecond precision on both the first post (`RETURNING`) and every replay (the load), through the same mapping, so the two bodies match exactly; an end-to-end test asserts it.
- **Convention (added to PROJECT-CONVENTIONS.md):** writes go through use cases; controllers call use cases for writes and ports directly for reads; ports report state, use cases decide, the domain stays pure.

### 6. Error mapping: one new 422, the 409 retired
- `IdempotencyKeyReusedException` (domain, unchecked) → `422`, new `ProblemTypes.IDEMPOTENCY_KEY_REUSED` = `urn:ledger-split-engine:problem:idempotency-key-reused`, same handler style as the other 422s. Message: `Idempotency-Key "<key>" was already used for a different request` — names the key, says nothing about the stored lines, and contains no class or package name (the QA support code rejects those in `detail`).
- `DuplicateIdempotencyKeyException` and its 409 handler are deleted. A `DuplicateKeyException` coming from below falls to the catch-all → 500 (kept from slice 3).

### 7. Sequencing: expand → migrate → contract
Renaming the port method would leave the build red between tasks (adapter, controller and several test classes all reference `post`). So the work is staged to stay green: (1) add the new domain types; (2) *expand* — add `insertOrFind` to the port and adapter alongside the existing `post`, leaving all existing tests untouched; (3) *migrate* — add the use case, move the controller to it, add the new handler and tests; (4) *contract* — delete `post`, `DuplicateIdempotencyKeyException` and the 409 handler, and move the remaining callers (adapter tests, two seed helpers) to `insertOrFind`. The dual API exists only inside the change and is gone at its end.

### 8. Tests
- **Domain:** `sameRequestAs` — identical lines true; a different amount, a different account, reordered lines, an extra line, a missing line each false (each differs in exactly one way).
- **Use case (no Spring, hand-written fake port):** `Created` → `Created`; `AlreadyExists` with identical lines → `Replayed`; `AlreadyExists` with different lines → `IdempotencyKeyReusedException`; the fake records that the use case never writes on a replay.
- **Adapter (Testcontainers, real commits, as `ledger_app`):** new key → `Created`, rows written; existing key → `AlreadyExists` with the original `entry_id`, `created_at` and lines in `line_number` order, row counts unchanged; an old entry (inserted by the owner with a backdated `created_at`) still replays — keys do not expire; new key with an unknown account → `UnknownAccountException`, no orphaned header, and the same key then returns `Created`; the declared isolation is `READ_COMMITTED` (a reflection test on `insertOrFind`'s `@Transactional` — the concurrency tests cannot cover this, because `READ COMMITTED` is also the database default and they pass with or without the annotation).
- **Deterministic concurrency (SQL level, two raw `ledger_app` connections):** A inserts a header with key K and holds the transaction open; B's `ON CONFLICT` insert runs in another thread. The test polls `pg_stat_activity` (through the owner connection) until B shows `wait_event_type = 'Lock'`, asserts B's future is not done, then A inserts its lines and commits: B returns no row and then reads A's entry. Second case: A rolls back instead, and B's insert returns a row. No sleeps; a bounded poll with a deadline. This needs a new `appConnection()` helper on `AbstractIntegrationTest`. **B runs inside an explicit transaction (`setAutoCommit(false)`) in both cases and ends by rolling back.** In the rollback case B's insert creates a header with no lines; in autocommit that statement would commit immediately and the slice 3 min-lines trigger would raise `LDG03` at the implicit commit, so B would get an exception instead of the row (observed on 16). B asserts the returned row, then rolls back.
- **Probabilistic race (use case + real adapter, two threads, start latch, 20 rounds):** same payload → exactly one `Created` and one `Replayed`, one entry; different payloads → exactly one `Created` and one `IdempotencyKeyReusedException`, one entry, and the stored lines are the winner's.
- **Controller (MockMvc, use case mocked):** `Created` → 201 and no replay header; `Replayed` → 201, the stored body, `Idempotent-Replayed: true`; `IdempotencyKeyReusedException` → 422 with the new type; a `DuplicateKeyException` still → 500, not 409.
- **End to end:** POST then identical POST — both 201, bodies equal (`entry_id`, `created_at`), second has the header, balances count the entry once; same key with a different amount, and with reordered lines → 422 reused, balances unchanged; a failed request does not consume the key (unknown account → 422 `unknown-account`, then the same key with valid accounts → 201, no header).
- **One defect per test (kept).** Reuse tests change exactly one thing about a valid retry. The two ordering tests necessarily carry two defects — a reused key plus an unbalanced body, and a reused key plus an unknown account — so they are named `precedence_…` and assert which rule wins (`unbalanced-entry`, `idempotency-key-reused`).
- **Existing tests rewritten:** `JdbcJournalEntryAdapterTest` (`post` → `insertOrFind`; the repeated-key test is replaced by the `AlreadyExists` tests); `LedgerAppPrivilegesTest` and `AppendOnlyTriggersTest` (seed helpers); `JournalEntryControllerTest` (mocks `PostJournalEntry`; `duplicateIdempotencyKeyReturns409ProblemJson` becomes the reuse/replay tests); `JournalEntryApiIntegrationTest.repeatedIdempotencyKeyReturns409ProblemJson` (becomes the replay test). `AccountBalanceControllerTest` and the DB-level trigger/backstop tests do not change.

### 9. The QA-owned contract suite: nine invocations will fail, none are edited
Checked against the current contract sources. They assert the retired 409 and are left red for QA to update from the new spec:
- `JournalEntryContractTest.reusedKeyDifferentLines` (expects 409 → now 422 `idempotency-key-reused`)
- `JournalEntryContractTest.identicalRepeatConflicts` (expects 409 → now 201 replay)
- `JournalEntryContractTest.concurrentSameKey` (12 different payloads: expects one 201 and the rest 409 → the rest are now 422)
- `JournalEntryGuardsContractTest.nonRuleErrorsKeepDefaultType`, the `409 reused Idempotency-Key` case (now a 422 with a ledger URN)
- `LedgerAppendOnlyContractTest.postedEntryCannotBeChanged` × 4 and `correctionIsANewEntry` (each re-posts the identical body and expects 409 → now 201 replay)

Still pass, by design of this slice: `reusedKeyUnbalancedBody` (accepts 409 or 422; gets 422 `unbalanced-entry`), `precedenceUsedKeyOnRepeatedAccount` (422 degenerate — validation first), `keyFromRejected422/400IsReusable`, `keysAreCaseSensitive`, `repeatedAccountIsNot409`. One latent fragility, not a failure on the fresh per-run container: `singleCharKeyAccepted` skips a key only on 409, so against a database that already used a one-character key it would now see a 422 and fail. The apply step re-runs `./mvnw verify` and reports against the current baseline rather than this prediction; the contract suite was changed by QA after slice 3, so the baseline must be re-measured first.

## Risks / Trade-offs

- [Correctness depends on `READ COMMITTED`] → Pinned in code (Decision 2): `insertOrFind` declares `@Transactional(isolation = Isolation.READ_COMMITTED)`, so a changed database default cannot turn a valid retry into a `40001` → 500. A reflection test asserts the annotation; the concurrency tests cannot, because `READ COMMITTED` is also the default. The pin holds because the adapter starts the transaction — the use case is deliberately not `@Transactional` (Decision 5); Spring ignores a method's isolation when it joins a caller's transaction.
- [A hung first request blocks retries of the same key until its transaction ends] → Transactions here are short and no lock or statement timeout is configured. A retry waiting on a stuck transaction is the correct behavior; a timeout is a separate operational decision.
- [Identity values are consumed by replays and rollbacks] → Gaps in internal ids only; never exposed.
- [Stored entries are re-validated on replay] → See Decision 4: a future rule tightening would need to grandfather older entries.
- [`sameRequestAs` compares only lines] → Correct today, since lines are the whole request. Slice 5 must extend it with any new request field (Decision 4).
- [A replay returns the body shape of the current code, not the original bytes] → The body is rebuilt from the stored entry. If the response format changes in a later release, a replay of an old entry returns the new format. Acceptable.
- [Keys are kept forever] → Matches the requirement; storage grows with entries, which are never deleted anyway.
- [Nine contract tests go red] → Listed above and in the final report so the red is not mistaken for a regression; the contract files are not edited.
- [Dual port API during the change] → Exists only between tasks 2.1 and 4.1 and is removed in the same change (Decision 7).

## Migration Plan

No schema or grant change and no data change: existing entries keep their keys, and `journal_entries_idempotency_key_uq` already exists. Deploy is a code-only change. Rollback is reverting the code. Clients that handled 409 on a repeated key must switch to treating 201 + `Idempotent-Replayed: true` as success and 422 `idempotency-key-reused` as a bug on their side.
