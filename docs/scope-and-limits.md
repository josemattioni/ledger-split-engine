# Scope and limits in depth

What the [README](../README.md#scope-and-known-limitations) summarizes about what this ledger does not do yet, with the
detail behind it. Nothing here is built; it is what was found and measured, written down so it is not rediscovered.

## Structural gaps for card split and settlement

These are in the data model, not only in missing endpoints:

- **No entry metadata.** An entry is its lines and an idempotency key: no event type, description, external reference
  (sale, NSU, authorization code, installment) or actor, so reconciling or auditing would mean encoding it in the key.
- **Only recording time.** `created_at` is when the entry's transaction started; there is no effective or settlement
  date, hence no receivables schedule, no future D+N balance, no "available at business-day close". The as-of balance
  answers "when did the database record it", not "when did the economic event occur".
- **Installments do not fit one entry.** An account may appear on only one line of an entry, so 12 installments on one
  receivable account are 12 entries (not atomic with the capture) or one account per installment.
- **No balance limit, no serialization point.** Nothing stops a negative balance. A payout that must not exceed the
  available balance needs a per-account lock, and `SELECT … FOR UPDATE` is refused to `ledger_app` (no `UPDATE`
  privilege). An advisory lock (`pg_advisory_xact_lock`) or `SERIALIZABLE` are candidates; neither is designed.
- **Reversal is total, once and final.** Partial refunds and chargebacks are ordinary entries, but there is no dispute
  lifecycle: a re-presentation is a new posting, and a chargeback after settlement against a seller with no balance
  would need offsetting against future receivables, which needs the effective date above.
- **Idempotency keys are global and permanent.** No client or tenant scope; with several clients it would become
  `UNIQUE (client_id, key)`.

To grow toward settlement, the first changes would be an `effective_date`, an `event_type` and an `external_ref` on the
entry header, and an explicit per-account locking strategy for postings that carry a balance limit.

## Balance reads and scale

A balance is never stored: it is the sum of the account's `entry_lines`, so a read costs time proportional to the number
of lines the account has. The as-of read also joins `journal_entries` to filter on `created_at`.

**Measured.** Postgres 17 in Docker on a development machine, the real migrations (V1 to V6), 2M entries and 4M lines: 2M of them on
one account (`acquirer_fee`, a line per sale) and about 2k on each of 1,000 sellers, `created_at` spread over 400 days,
warm cache, one connection, the queries exactly as `JdbcJournalEntryAdapter` issues them. A second run on another
machine gave the same plan with smaller constants (120 to 150 ms and about 1.2 s).

| Read | Account with 2M lines | Seller with 2k lines |
|---|---|---|
| Current balance | about 0.3 s | about 2 ms |
| As-of balance, any instant | about 1.7 s | about 8 ms |

The as-of cost does not depend on the instant asked for: an instant covering half the history took as long as one
covering nearly all of it, because the filter on `created_at` runs after one primary-key lookup per line of the account
(2M lookups, about 8M buffers). The planner estimated 3,996 rows for 2,000,000: `a.code = ?` hides the account id, so
it uses the average lines per account, although its statistics know that one id holds 50% of the lines (it uses that
only when the id is a literal).

**Where it stops (extrapolated, assuming linear cost and a warm cache; a cold cache or an index larger than memory is
worse).** The slope is about 0.9 µs per line for the as-of read and 0.15 µs for the current balance, so an as-of read
passes 30 seconds at about 35M lines on one account and the current balance at about 200M. The read runs as
`ledger_app` under the 30-second `transaction_timeout`, and that bound ends the session, it does not cancel the
statement: Postgres terminates the connection (`FATAL`, `25P04`), the API answers its generic `500`, and the pooled
connection is lost. The pool has Spring Boot's default size (10) and there is no authentication, so slow reads on a
hot account can also compete with every other request for connections (inferred, not measured with the application).

Only accounts that accumulate a line per business event are affected, platform accounts such as fees and commission. A
seller's account stays small.

**What works today, with no code change.** Split a hot account by period in its code (`acquirer_fee:2026-10`): the code
format already allows `:identifier` and accounts are immutable, so each bucket stays bounded. The caller adds the
buckets up, so the cost moves to the caller.

**What would scale (not built; each measured on a copy of the same data).**

- *A smaller constant.* Copy `created_at` onto `entry_lines` (default `now()`, so it equals the header's by
  construction: both are written by the one transaction that `LDG06` requires) and index
  `(account_id, created_at) INCLUDE (amount_cents)` in place of the `account_id` index. The as-of read becomes an
  index-only range scan: 0.25 s for nearly everything, 0.13 s for half, 13 ms for an instant covering about 100k lines,
  0.3 ms for a seller. The current balance stays at about 0.25 s and the cost stays linear (about 0.125 µs per line,
  roughly 240M lines to reach 30 s). It costs space (on the 4M-line table the new index is 155 MB where the old one was
  28 MB; in a separate 2M-line test the table with all its indexes grew 45%), time on writes (a bulk insert of 2M
  lines took 22% longer, not measured under concurrency), and a backfill of existing rows that needs the append-only
  triggers off (2 minutes for 4M lines), so it is cheapest while the ledger is empty.
- *A better query shape.* Resolving the account id first and passing it as a literal gives the planner the right
  estimate (0.3 s). It is not relied on: after 5 executions the JDBC driver's server-side prepared statement switches
  to a generic plan that does not know the value, and the plan is bad again (0.6 s in the same test).
- *Checkpoints.* A closing balance per account and day, plus the sum of the tail after it: summing one day of the 2M-line
  account took 0.5 ms, because the cost follows the tail and not the account. This is the only one of the three that changes the growth. It makes a stored balance a
  cache derived from the lines, which contradicts "balances are never stored" and so would have to be stated as such;
  it would only be valid for settled instants, on the same 60-second argument as the as-of balance; and it would need a
  job that recomputes and compares, because a late commit would be frozen into the checkpoint instead of only making one
  as-of read unrepeatable. Partitioning `entry_lines` by time helps retention, not the latency of a balance.
- *Failing cleanly.* A `statement_timeout` shorter than the transaction bound on the balance reads would cancel the
  statement (`57014`) and keep the session, instead of losing the connection.
