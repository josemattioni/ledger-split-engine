## Why

Slice 1 gave the engine accounts to post against but no way to move money. This is the first slice where money actually moves: a client posts a balanced, multi-line journal entry against existing accounts, and can read back the derived balance of any account. This also lays down the append-only `entry_lines` schema and the deferred-trigger balance enforcement that every later slice (idempotent replay, reversal, currency-grouped balances, as-of balance) builds on top of.

## What Changes

- Add `journal_entries` and `entry_lines` tables (Flyway `V2`). `entry_lines` FKs to `accounts.id` (internal only); `journal_entries` carries the external `entry_id` (UUID) and `idempotency_key`.
- Add a Postgres deferred constraint trigger on `entry_lines` that re-checks, at commit, that every journal entry's lines sum to zero — a backstop behind the domain-layer check, mirroring the two-layer validation pattern from slice 1.
- Add `POST /api/v1/journal-entries` to post a balanced multi-line journal entry, gated by a required `Idempotency-Key` header (format only in this slice — replaying the original response on a repeat key is slice 4's job; this slice just turns a repeat key into a clean 409).
- Add `GET /api/v1/accounts/{code}/balance` to read an account's balance, derived from its entry lines (never stored).
- Extend `PROJECT-CONVENTIONS.md` with the balance-sign convention: `balance_cents` is the raw signed sum (debit positive, credit negative), not normalized by account type.
- Extend the error-mapping convention with a 422 tier (well-formed request, violates a ledger rule) alongside the existing 400/404/409/500.

**Explicitly out of scope** (reserved for later slices, matching the input spec's own scoping): append-only triggers guarding against updates/deletes; degenerate-entry guards (empty entry, single-line entry, zero-amount line, same account twice); idempotent replay of the original response; reversal; currency-grouped trigger and multi-currency balance; account field locking; as-of-date balance.

## Capabilities

### New Capabilities
- `journal-entries`: posting a balanced, multi-line journal entry against existing accounts, and the validation rules (balance, account existence, currency consistency, idempotency key) that gate it.
- `account-balances`: reading an account's balance, derived from its journal entry lines. Split from `journal-entries` because it has an independent read-side lifecycle — later slices (as-of balance, currency-grouped balance) extend this without touching posting behavior, and posting-only concerns (idempotent replay, reversal) extend `journal-entries` without touching balance reads.

### Modified Capabilities
(none — `account-registration`'s purpose explicitly excludes journal entries and balances, so nothing about it changes here)

## Impact

- New code: `domain.journalentry` package (`JournalEntry`, `EntryLine`, `JournalEntryPort`, new domain exceptions), a JDBC adapter implementing `JournalEntryPort`, a new web package with `JournalEntryController` (`POST /journal-entries`) and `AccountBalanceController` (`GET /accounts/{code}/balance`).
- `ApiExceptionHandler` gains a 422 tier for the new ledger-rule exceptions (unbalanced entry, unknown account, mixed currency).
- New Flyway migration `V2__create_journal_entries.sql` (tables + trigger function + trigger).
- New adapter-level tests that insert directly via `JdbcTemplate` to exercise the deferred trigger (unreachable from HTTP, since the domain layer already rejects an unbalanced entry before any insert).
- `PROJECT-CONVENTIONS.md` and `README.md` updated for the new endpoints and the balance-sign convention.
- No existing `accounts` table or `account-registration` behavior changes.
