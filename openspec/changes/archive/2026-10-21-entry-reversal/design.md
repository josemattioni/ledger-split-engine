## Context

See proposal.md for the motivation and the specs for the requirements. Current state that shapes the design:

- `JournalEntry` is a record `(entryId, idempotencyKey, lines, createdAt)` whose compact constructor holds every rule that needs no stored data. It has 3 call sites in `src/main` and 43 in `src/test`. `sameRequestAs` compares only `lines`; slice 4's design says any new request field must join it and be persisted.
- `JournalEntryPort` has `insertOrFind` and `balanceOf`. `insertOrFind` runs one `@Transactional(isolation = READ_COMMITTED)` method: `INSERT … ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING RETURNING`; a returned row means the key is new (resolve accounts, insert lines), no row means load the stored entry by key and report `AlreadyExists`. `PostJournalEntry` compares and decides.
- `ledger_app` can only `SELECT` and `INSERT` and cannot take row locks. Every ledger rule has a database backstop with its own SQLSTATE (`LDG01` balance, `LDG02` append-only, `LDG03` two-line minimum).
- A `400` is raised only by `InvalidRequestException`, wrapped around request-to-domain construction. An `IllegalArgumentException` raised anywhere else, including inside a use case, is a generic 500. This matters for Decision 2.
- `journal_entries` has `id BIGINT GENERATED ALWAYS AS IDENTITY` (internal) and `entry_id UUID` (public).

## Goals / Non-Goals

**Goals:**
- A reversal built only by the server, correct under retries and concurrency, with no lock that `ledger_app` lacks.
- The invariants a reversal depends on enforced twice, in the domain and in the database, as in every earlier slice.
- A retry of a successful reversal always replays; it never reports `entry-already-reversed`.

**Non-Goals:**
- Partial reversal, a reason or description on a reversal, `GET /journal-entries/{entry_id}`, and anything in slice 6.
- A pre-check query for "already reversed". The constraint is the authority (Decision 3).
- Changing how an ordinary entry is posted, apart from the one added response field.

## Decisions

### 1. Domain: one new field, one factory, a wider comparison
`JournalEntry` becomes `(UUID entryId, String idempotencyKey, List<EntryLine> lines, Instant createdAt, UUID reversesEntryId)`. A secondary four-argument constructor delegates with `null`, so none of the 46 existing call sites changes. `isReversal()` is `reversesEntryId != null`.

`JournalEntry.reversalOf(JournalEntry original, String idempotencyKey)` is a pure factory:
- `original.isReversal()` → `CannotReverseAReversalException(original.entryId())` (a `RuntimeException`, not an `IllegalArgumentException`, so it is a 422 and not a 400 or a 500).
- `original.entryId() == null` → `IllegalArgumentException`. A reversal is only ever built from a loaded entry, so this is a programming defect and must stay a 500.
- Lines are `(same lineNumber, same accountCode, Math.negateExact(amountCents))`; `reversesEntryId = original.entryId()`; `entryId` and `createdAt` are `null` (server-assigned on insert); the result goes through the validating constructor. The original already satisfied every rule and the per-line cap is symmetric, so the negation does too. `negateExact` rather than `-` so a stored value that somehow broke the cap fails loudly instead of wrapping.

`sameRequestAs` becomes `lines.equals(other.lines) && Objects.equals(reversesEntryId, other.reversesEntryId)`. Both sides are the *public* UUID, because the adapter loads the stored entry's `reversesEntryId` as the original's `entry_id` (Decision 6). The comparison on `reversesEntryId` is not redundant with the lines: an ordinary entry whose lines are exactly the negation of T's lines, and the reversal of T, have identical lines under the same key; only the link tells them apart (spec: "Identical lines are still a different request if one is a reversal").

- **Alternative rejected: a separate `Reversal` type.** Reversal is an entry that differs by one link; a second type would need its own port methods, persistence mapping and response, and `sameRequestAs` would have to compare across types.

### 2. The key shape moves into the domain so the controller can validate it first
Today the key's shape is checked inside `JournalEntry`'s constructor, and the controller turns that `IllegalArgumentException` into a 400 because it wraps the construction. A reversal calls `reversalOf` *inside the use case*, where an `IllegalArgumentException` is a 500 by the rule above. A malformed key on the reversal endpoint would therefore be a 500 unless it is validated before the use case runs, which the evaluation order also requires (path and header are step 1).

So the rule is extracted into `domain.journalentry.IdempotencyKey` (`MAX_LENGTH`, `isWellFormed`, `requireWellFormed`, mirroring how `AccountCode` was extracted in the last change). `JournalEntry` calls it with the same message, byte for byte. The controller reads the header once for both endpoints, checks it appears exactly once, and calls `requireWellFormed` inside `InvalidRequestException.guard`, before any use case.

- **Alternative rejected: catch `IllegalArgumentException` around the use case.** That is the blanket mapping the last change removed.

### 3. Evaluation order, and why a retry always replays
Order: path and header (400) → load target by `entry_id` (404) → `reversalOf` (422 for a reversal as target) → `PostJournalEntry.execute` (insert-or-find on the key: `Created`, `Replayed`, or `IdempotencyKeyReusedException`) → the at-most-once constraint (422).

The use case does not look for an existing reversal before inserting. If it did, a retry of a successful reversal would find that reversal and report `entry-already-reversed`; and two concurrent requests would both pass the check. The unique constraint on the link is the single authority. The key's own `ON CONFLICT` arbiter runs before it whenever the other request's row is already visible, which is why a *sequential* retry replays; the simultaneous case is different, and is corrected below.

Observed on Postgres 16.15, as `ledger_app`, against V1–V3 plus the V4 below (scratch database, since dropped):
- Existing key, target already reversed → `INSERT … ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING RETURNING id` returns **no row and no error**, and the transaction stays usable. The same holds when the target is a different, unreversed entry (the use case then reports a reuse).
- New key, target already reversed, same `ON CONFLICT` clause → `23505` on `journal_entries_reverses_entry_uq`, raised by the statement itself.
- Concurrent, A holds an uncommitted reversal of T: B with a **different key** waited about 3 s (one backend with `wait_event_type = 'Lock'`), then failed with `23505` after A committed, and proceeded after A rolled back. B with the **same key** waited, then got no row and no error after A committed.

**Correction found during implementation: a simultaneous same-key retry.** The probes above run one request after the other (B starts once A's row exists), so B's key check always sees A. When both requests are *in flight at once*, both can pass the key's pre-check before either row is visible; each then inserts its index entries one by one, and the reversal's unique index, which is not the `ON CONFLICT` arbiter and so raises immediately, can meet the other request's entry before the key conflict is resolved. The retry then fails with the at-most-once violation although it is only a retry, which the spec forbids ("Simultaneous retries under the same key"). The 20-round race test of the same key failed at rounds 7-9 on every one of 3 runs, and at round 2 of 300 with the fix disabled.

The fix is a second look in the use case (Decision 7): when the first attempt ends in the at-most-once violation, ask once more. The violation is only raised after the other request has committed, so the second attempt's key check sees its row and answers with the replay (or a reuse, if the other request was a different one). A genuine second reversal under a new key is refused again and the exception propagates. With the fix, 600 rounds across two runs were clean, in addition to the 5 runs of 20.

- **Alternative rejected: `ON CONFLICT DO NOTHING` with no conflict target.** It would swallow the reversal violation as "no row", and the adapter would then have to tell "the key exists" from "the entry is reversed" from a collision on an unrelated unique index, losing the by-name classification that keeps every other unique violation a 500.
- **Alternative rejected: retry inside the adapter.** The failed statement aborts its transaction, so the retry needs a new one; the use case is not transactional and already owns "what an existing key means".

Only `journal_entries_reverses_entry_uq` is mapped to `entry-already-reversed`. Every other unique violation still propagates as a 500, as in slices 3 and 4. The detail names the original's `entry_id`, which the request already carries, so nothing is read after the aborted statement.

### 4. Schema V4 (additive, verified)
```sql
ALTER TABLE journal_entries
    ADD COLUMN reverses_entry_id BIGINT NULL,
    ADD CONSTRAINT journal_entries_reverses_entry_fk
        FOREIGN KEY (reverses_entry_id) REFERENCES journal_entries (id),
    ADD CONSTRAINT journal_entries_reverses_entry_uq UNIQUE (reverses_entry_id);

CREATE FUNCTION check_reversal_matches_original() RETURNS trigger AS $$
DECLARE
    original_is_reversal BOOLEAN;
BEGIN
    SELECT reverses_entry_id IS NOT NULL INTO original_is_reversal
    FROM journal_entries WHERE id = NEW.reverses_entry_id;
    IF original_is_reversal THEN
        RAISE EXCEPTION 'journal entry % reverses entry %, which is itself a reversal', NEW.id, NEW.reverses_entry_id
            USING ERRCODE = 'LDG05';
    END IF;

    IF EXISTS (
        (SELECT line_number, account_id, amount_cents FROM entry_lines WHERE journal_entry_id = NEW.id
         EXCEPT
         SELECT line_number, account_id, -amount_cents FROM entry_lines WHERE journal_entry_id = NEW.reverses_entry_id)
        UNION ALL
        (SELECT line_number, account_id, -amount_cents FROM entry_lines WHERE journal_entry_id = NEW.reverses_entry_id
         EXCEPT
         SELECT line_number, account_id, amount_cents FROM entry_lines WHERE journal_entry_id = NEW.id)
    ) THEN
        RAISE EXCEPTION 'journal entry % is not the exact negation of entry %', NEW.id, NEW.reverses_entry_id
            USING ERRCODE = 'LDG04';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entries_reversal_matches
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.reverses_entry_id IS NOT NULL)
    EXECUTE FUNCTION check_reversal_matches_original();
```
The final file carries comments in the style of V3. Observed against that exact SQL, on a database already holding an entry from before V4:
- It applies in one pass; the pre-existing row reads `reverses_entry_id = NULL`; no row is rewritten and the append-only triggers do not fire. `ledger_app` needs no new grant (table-level `INSERT` covers the column).
- A proper reversal commits. Every wrong shape fails at `COMMIT` with `LDG04`: lines not negated; a **balanced but wrong** three-line reversal (the case the balance trigger cannot catch); a missing line; an extra line; right amounts under swapped `line_number`s. A reversal of a reversal fails with `LDG05`.
- A second reversal fails at insert with `23505` on `journal_entries_reverses_entry_uq`.
- **Self-reference and cycles fail with `LDG05` at `COMMIT`** (explicit ids through `OVERRIDING SYSTEM VALUE`, which `ledger_app` may use, with two balanced lines per row):
  - a row whose `reverses_entry_id` is its own id → `LDG05`, "journal entry 900000 reverses entry 900000, which is itself a reversal";
  - two rows that reverse each other in **one multi-row `INSERT`** → `LDG05`, reported by the **first row in the `VALUES` list**; swapping the list order swaps the reporting row, so which row reports is deterministic (rows 800001 → 800002 and 700002 → 700001 in the two probes);
  - a chain A ← B ← C (C reverses B) → `LDG05`.

Notes:
- **Set comparison is exact** because an account appears at most once per entry (slice 3). The `EXCEPT` runs in both directions so a missing and an extra line are both caught. The trigger fires per header row at commit, after the lines exist. Header triggers are queued before line triggers and fire in name order, so a fixture must have two balanced lines or `LDG03` (`trg_journal_entries_min_lines`, which sorts first) reports instead; with that, a single-defect fixture sees only its own code.
- **Why there is no `CHECK (reverses_entry_id < id)`, and why self-reference and cycles are still impossible.** An ordering check on the internal id would depend on the identity sequence issuing values in draw order, and it is redundant with what is already enforced:
  - *Self-reference* (an explicit id through `OVERRIDING SYSTEM VALUE`): the "original" is the row itself, which is a reversal, so `LDG05` fires at commit.
  - *A cycle across statements* cannot be built: the foreign key requires the referenced row to exist, and closing a loop afterwards needs an `UPDATE`, which append-only forbids (`ledger_app` has no privilege, and the owner is stopped by `LDG02`; both were observed).
  - *A cycle inside one multi-row `INSERT`* with explicit ids (X → Y, Y → X): each row's original is a reversal, so `LDG05` fires at commit.
  - One consequence, observed: a reversal whose id is *lower* than its original's is accepted, which is reachable only by inserting the original with an explicit high id. It is harmless: the original exists and was committed first, it is not a reversal, the reversal is the only one, and its lines negate the original's. The application never supplies an id.
- **`LDG05` is an addition to the brief, and it carries real weight.** Every other domain rule has a database backstop, and without this one a reversal of a reversal (domain-checked only) would pass every other constraint: its lines equal the original movement and it balances. It is also what rules out a self-referencing or cyclic link, as above. The cost is one `SELECT` and one condition in a trigger that already exists.
- **The link lives on the reversal only.** The original row is never updated, and `ledger_app` could not update it anyway; an owner `UPDATE` of `reverses_entry_id` is still refused by `LDG02` (a test pins this).
- **Alternative rejected: a separate `reversals` table.** It would give the link a home away from the header, but the header already carries every per-entry attribute, and the `UNIQUE` above is simplest on the column.

### 5. Adapter write path: resolve the original's internal id explicitly
The brief suggests resolving the link inside the insert with a scalar subquery on `entry_id`. Observed on Postgres 16.15: with an `entry_id` that matches nothing, the subquery yields `NULL`, and the insert **succeeds as an ordinary entry** (`reverses_entry_id IS NULL`), whose lines are then inserted too. The use case loads the target first, so the case is unreachable today, but the failure it hides — a reversal silently posted as an ordinary entry — is exactly the kind a ledger must not have.

So `insertOrFind`, for an entry whose `reversesEntryId` is not null, first runs `SELECT id FROM journal_entries WHERE entry_id = ?` in the same transaction and throws `IllegalStateException` if it finds nothing (entries are never deleted, so absence is a defect and a 500). The header insert then carries the `BIGINT` as a bound parameter (an explicit `Types.BIGINT`, so a `null` for ordinary entries is typed):
```sql
INSERT INTO journal_entries (idempotency_key, reverses_entry_id) VALUES (?, ?)
ON CONFLICT ON CONSTRAINT journal_entries_idempotency_key_uq DO NOTHING
RETURNING id, entry_id, created_at
```
It is wrapped in a `try` that catches `DuplicateKeyException`, reads the violated constraint name from the `PSQLException`, and for `journal_entries_reverses_entry_uq` only throws `EntryAlreadyReversedException(entry.reversesEntryId())`; anything else is rethrown. The exception propagates out of the `@Transactional` method, which rolls the whole transaction back, so a rejected reversal leaves no header and its key stays unused. `Created` carries `reversesEntryId` from the request. Account resolution and the currency check run unchanged for a reversal (they cannot fail for a stored original).

- **Alternative kept as the fallback: the subquery plus a Java-side check.** Rejected: the check would have to detect the `NULL` after the fact, by which point the row is written.

### 6. Adapter read path: one load, two lookups
The query behind `loadByKey` becomes `loadWhere(column, value)` over two constant SQL strings that differ only in the predicate, so the key lookup and the new `findByEntryId(UUID)` cannot drift:
```sql
SELECT je.entry_id, je.idempotency_key, je.created_at, orig.entry_id AS reverses_entry_id,
       el.line_number, a.code, el.amount_cents
FROM journal_entries je
LEFT JOIN journal_entries orig ON orig.id = je.reverses_entry_id
JOIN entry_lines el ON el.journal_entry_id = je.id
JOIN accounts a ON a.id = el.account_id
WHERE je.<column> = ?
ORDER BY el.line_number
```
The rows are rebuilt through the validating constructor, as slice 4 decided (a stored entry is re-checked on every load). `findByEntryId` returns `Optional.empty()` when there are no rows (an entry cannot exist without lines: `LDG03`); `loadByKey` keeps its `IllegalStateException` for "conflicted but not found". Both set `reversesEntryId` to the original's public UUID, which is what `sameRequestAs` compares. `findByEntryId` is a single read and needs no transaction of its own.

### 7. Application: a thin use case, no transaction, no pre-check, one second look
`ReverseJournalEntry.execute(UUID entryId, String idempotencyKey)` returns `PostJournalEntryResult`:
```
original = port.findByEntryId(entryId).orElseThrow(JournalEntryNotFoundException)
reversal = JournalEntry.reversalOf(original, idempotencyKey)
try { return postJournalEntry.execute(reversal) }
catch (EntryAlreadyReversedException) { return postJournalEntry.execute(reversal) }   // the second look (Decision 3)
```
It is not `@Transactional`: the target is immutable, and "at most once" is a constraint, not a lock (`ledger_app` cannot take row locks). `JournalEntryNotFoundException` is a domain exception (a `RuntimeException` in `domain.journalentry`) so `application` does not depend on `adapter.web`. Replay, reuse and creation are all `PostJournalEntry`'s existing behavior; nothing is duplicated.

### 8. Web
- **Endpoint** in `JournalEntryController`: `POST /{entry_id}/reversal`; the response is the existing `JournalEntryResponse`, `201`, plus `Idempotent-Replayed: true` for a replay. No `Location`, like `POST /journal-entries`.
- **Representation.** `JournalEntryResponse` gains `reversesEntryId`, serialized `reverses_entry_id` by the global naming strategy, after `created_at`. Nulls are included by this project's mapper (an account response already shows `"owner_ref": null`), so an ordinary entry shows `"reverses_entry_id": null`; the tests assert the key is *present* with a null value, not merely absent. A replay body equals the original byte for byte.
- **Path value.** The path variable is read as a string and checked against the canonical 8-4-4-4-12 hexadecimal form (either letter case) inside `InvalidRequestException.guard` before `UUID.fromString`. Java alone accepts `1-1-1-1-1` as `00000001-0001-0001-0001-000000000001` (observed on JDK 21), which would turn a malformed id into a 404 or, worse, a hit. This follows the project's reject-don't-normalize rule; letter case is accepted because a UUID is case-insensitive text.
- **Body.** The endpoint declares `@RequestBody(required = false) byte[]` and rejects a non-empty body with 400. This is beyond the brief's "no request body", and the reason is safety: ignoring a body would answer a client that tried to send lines (a partial reversal) with a full reversal. The task verifies the converter handles JSON, `{}`, plain text, an empty body with a JSON content type, and no body.
- **Errors.** `ProblemTypes.CANNOT_REVERSE_A_REVERSAL` and `ENTRY_ALREADY_REVERSED`, one 422 handler each, through the existing `ledgerRuleViolation`; `JournalEntryNotFoundException` → 404 with the default type (like every other non-422 error), detail `No journal entry with entry_id "<uuid>"`. Messages: `entry <uuid> is itself a reversal and cannot be reversed; to undo it, post the original movement again as an ordinary entry` and `entry <uuid> has already been reversed`.

### 9. Tests
Each negative test has one defect and asserts the rule-specific type; a test that necessarily has two is named `precedence_…`.
- **Domain:** `reversalOf` negates every line and keeps `line_number` and `account_code`; sets `reversesEntryId`; has a null `entryId`/`createdAt`; keeps the key; reverses a 100-line entry at ±the cap; rejects a reversal as target (message names it); rejects an original with no `entryId`. `sameRequestAs`: different `reversesEntryId` → false; null vs non-null → false; equal both → true. `IdempotencyKey` cases; existing key tests still pass unchanged.
- **Use case (fake port, real `PostJournalEntry`):** missing target → not found, nothing written; reversal as target → `CannotReverseAReversalException`, nothing written; ordinary target → the port receives the negated reversal; same key and target again → `Replayed`; a key used by an ordinary entry, or by a reversal of another entry → `IdempotencyKeyReusedException`; a genuine second reversal under a new key is refused after the port has been asked exactly twice; and a fake that makes a same-key request commit first and report the violation (the simultaneous retry) → the second look → `Replayed`.
- **DB-level (real commits, as `ledger_app`, one defect each):** every case observed in Decision 4, plus ordinary entries unaffected by the trigger and an owner `UPDATE` of `reverses_entry_id` refused with `LDG02`.
- **Adapter:** `findByEntryId` (lines in order, `reversesEntryId` is the original's UUID or null, absent → empty); a reversal `Created` with the link stored; same key and target → `AlreadyExists` carrying the link; a different key → `EntryAlreadyReversedException` with nothing left behind and the rejected key still usable; an unknown target id → `IllegalStateException` with nothing inserted (this pins Decision 5).
- **Deterministic concurrency (SQL level, two raw `ledger_app` connections in explicit transactions, `pg_stat_activity` polling, no sleeps):** A holds an uncommitted reversal of T; B, running the adapter's own insert statement with a different key, blocks; A commits → B fails on `journal_entries_reverses_entry_uq`. Second case: A rolls back → B's insert returns a row. Third case: B with the same key blocks, then returns no row. The wait-for-lock poll and its failure message move out of `IdempotentInsertConcurrencyTest` into a shared test helper so both classes use one copy (behavior-neutral).
- **Controller (MockMvc, use case mocked):** each outcome and error; every malformed-UUID shape; missing, repeated and malformed key; the body cases; an `IllegalArgumentException` from the use case is a 500.
- **End to end:** the brief's list, plus an upper-case UUID path, and key-consumption cases (a rejected reversal leaves its key usable).
- **Race (use case on the real adapter, two threads released by a latch, 20 rounds, fresh original and accounts per round):** different keys → exactly one `Created` and one `EntryAlreadyReversedException`, one reversal stored. A second test with the same key (an addition to the brief): exactly one `Created` and one `Replayed`, one reversal stored — it exercises the precedence of Decision 3 under contention.
- **Existing tests** that assert the response shape gain `reverses_entry_id` (`null`); the repo-wide count of pre-existing tests must not drop.

### 10. The QA-owned contract suite
Checked against the current contract sources, not edited. `JournalSliceContractSupport.assertNoInternalId` treats any key ending in `_id` as an internal database id unless the caller allows it. Two tests allow only `entry_id` and will fail on the new key: `IdempotentReplayContractTest.replayBodyShape` and `JournalEntryContractTest.entryIdServerAssigned`. The spec now says `reverses_entry_id` is a public identifier (see "Journal entry identity is server-assigned"), so QA should add it to those allowed sets. Everything else that reads an entry response passes by design (`assertSnakeCaseKeys`, the body-equality helpers, field-by-field reads). No contract test covers reversal yet. The apply step re-runs `./mvnw verify` and reports against the current baseline rather than this prediction.

**A request Tomcat rejects before the application sees it.** Observed on the wire against the running app, over a raw socket: a DEL (0x7F) or `0x01` in the `Idempotency-Key` value is answered by Tomcat itself with `400` and its HTML "HTTP Status 400 – Bad Request" page (`text/html`); the request never reaches the `DispatcherServlet`, so no `@ExceptionHandler` can shape the response, and nothing is created. A tab, a space or a non-ASCII byte in the same header reaches the application and gets its `400` `application/problem+json`. The cause is the same on `POST /journal-entries` (known since slice 2) and on the reversal endpoint, so the specs now state the exception once per error-format requirement (`journal-entries` "Journal entry errors are reported as problem+json" and `journal-entry-reversal` "Reversal errors are reported as problem+json"): the status is still `400`, the body is the server's own page, nothing is created. The same applies to any request Tomcat rejects first (a bad percent-encoding in the path, an oversized request line or header, a missing `Host`); the spec names the control-character case as the example.

*Alternative not taken here: answer those requests in problem+json too.* A subclass of Tomcat's `ErrorReportValve` registered as the host's error-report class (through a `WebServerFactoryCustomizer<TomcatServletWebServerFactory>`) writes a problem+json body for every status Tomcat reports on its own. A prototype in a scratch copy of the project, about 50 lines in two classes under `config/` plus a raw-socket `@SpringBootTest` (MockMvc skips Tomcat), turned all of the cases above into problem+json and took the contract suite from 17 failures to 13 (exactly the four control-character cases), with every unit test green. It is server-wide plumbing, not a reversal concern, so it is kept out of this change and recorded here as a deliberate deferral; adopting it later would remove the exception from both requirements and need no change to the contract tests. Two details to settle then: a `505` should not say "An unexpected error occurred", and the content type would carry a `charset` parameter the application's own responses do not.

### 11. Conventions and docs
`PROJECT-CONVENTIONS.md` gains the reversal rule (the only correction mechanism: full, at most once, never of a reversal, lines built by the server, a partial correction is an ordinary entry) and "every request field that determines what is stored takes part in `sameRequestAs`" (it already says so; the new text states it as a rule). The README documents the endpoint, `reverses_entry_id`, the two problem types, the retry advice (derive the key from the entry, so a retry after a lost response replays instead of reporting `entry-already-reversed`), and the sequence dependency.

## Risks / Trade-offs

- [A client that retries a lost reversal with a *fresh* key gets `entry-already-reversed`, although its reversal succeeded] → This is correct (it cannot tell the retry from a second attempt) and is what the README's key advice addresses. The 422 detail names the entry, so the client can see the reversal exists.
- [`LDG05` is beyond the brief and load-bearing] → It is what rules out a reversal of a reversal, a self-referencing link and a cyclic link (Decision 4), so removing it would reopen all three. Three tests pin them (`ReversalConstraintsTest`).
- [A reversal can have a lower id than an original inserted with an explicit high id] → Reachable only through `OVERRIDING SYSTEM VALUE`, which the application never uses; every rule that matters still holds (Decision 4).
- [Rejecting a request body is stricter than the brief] → It prevents a partial-reversal attempt from becoming a full one; a client that sends nothing is unaffected. Verified in the controller test matrix.
- [The load query joins `journal_entries` to itself on every key replay] → A primary-key join on one row; negligible, and the unique index on `reverses_entry_id` is also what a future "is this reversed?" read would use.
- [A simultaneous same-key retry can surface as the at-most-once violation] → The use case asks a second time (Decision 3), which is when the key check sees the committed row. It costs one extra insert attempt only when a reversal is refused, and is pinned by a use-case test and by the 20-round same-key race (stressed at 300 rounds).
- [Two contract tests go red] → Listed above and in the apply report so the red is not mistaken for a regression.
- [The four control-character contract cases (DEL and `0x01`, on posts and on reversals) stay red until QA relaxes them] → The spec now says Tomcat answers these with its own `400` page (Decision 10), so the tests should expect status `400` and nothing created instead of problem+json. The red is a stale expectation, not a defect.
- [A reversal commit runs a trigger that compares two sets of up to 100 lines] → Bounded by the line limit; the same order of work as the balance trigger.

## Migration Plan

`V4__entry_reversal.sql` is applied by Flyway as the owner on the next start, on an existing database as well as a new one (verified on a database holding entries from before V4). No data change and no volume reset; the init script is not touched, so `docker compose down -v` is not needed. Code written before this change keeps working against V4, because the new column is nullable and an insert that omits it leaves it `NULL`. Rollback is reverting the code; V4 stays harmlessly in place, and removing it would be a new migration (an applied migration is never edited, so its checksum stays valid).
