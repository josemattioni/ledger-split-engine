# Append-only guarantees and their limits

The full account of what the database enforces and where it stops. The summary is in the
[README](../README.md#append-only-guarantees-and-their-limits).

Ledger rows are never edited or deleted: a correction is a new entry, never an `UPDATE`.
This is enforced in layers, and it's worth being precise about what each one stops.

1. **Privileges (the primary control).** The application connects as `ledger_app`, which
   holds `SELECT`, and `INSERT` only on the columns a client supplies (an account's `code`,
   `owner_ref` and `type`; an entry's `idempotency_key` and `reverses_entry_id`; a line's
   `journal_entry_id`, `line_number`, `account_id` and `amount_cents`). It has no `UPDATE`,
   `DELETE` or `TRUNCATE` on any table (`accounts` included), and it can't alter the schema or
   disable triggers. The columns the server assigns (`id`, `entry_id`, `created_at`,
   `currency`) can therefore only come from their defaults: an insert naming one is refused
   with a permission error, `OVERRIDING SYSTEM VALUE` included. That is what keeps a
   timestamp from being invented, which the as-of balance depends on. A table created by a
   later migration is readable by `ledger_app` and writable only through an explicit
   column-level grant in its own migration.
2. **Triggers (the second layer).** `BEFORE UPDATE OR DELETE` row triggers and a
   `BEFORE TRUNCATE` statement trigger on `accounts`, `journal_entries` and `entry_lines`
   refuse mutation with an "append-only" error — even for the table owner, which gets past the
   privileges. (A bare `TRUNCATE accounts` is stopped even earlier, because `entry_lines`
   references it.)
3. **An entry's lines are fixed when it is recorded (`LDG06`).** A trigger on `entry_lines`
   refuses a line unless its entry's header was inserted by the same transaction, for the
   application and for the owner. Without it, `ledger_app`'s `INSERT` on `entry_lines`, which
   covers any entry, would let two lines that cancel out (+5000 / -5000) be added to an entry
   committed days ago: every balance as of any instant after its `created_at` would change,
   which is what the as-of balance promises it never does, and the entry's reversal would no
   longer be its exact negation. Posting and reversing insert an entry and its lines together
   and are unaffected. It compares the header's `xmin` with the transaction's xid, so a header
   inserted inside a **savepoint** is not recognised (the application uses none: no nested
   transactions, no pgjdbc `autosave`), and the comparison has only 32 bits (it could be fooled only after the
   transaction counter wraps around, 2³² transactions later). A **restore** keeps an entry's header and lines together:
   `pg_restore --data-only --single-transaction` loads a ledger with every trigger on, and a full dump restores as it
   is; one transaction per table is refused by the two-line check, because a header is committed before its lines
   (`RestoreRecipeTest` runs both).
4. **The checks read the real tables (V7).** Every trigger function fixes its own `search_path` to `public, pg_temp`.
   Before, they named `entry_lines` and `journal_entries` without a schema, and Postgres searches the temporary schema
   first, which `ledger_app` can write to: a session could create a temporary table with a ledger table's name and the
   check read that one and passed. Reproduced as `ledger_app`: two lines that cancel out added to an entry committed
   earlier (`LDG06`), and an unbalanced entry committed (`LDG01`, `LDG03`); the reversal checks (`LDG04`, `LDG05`) read
   the same tables. With the path fixed, and `pg_temp` listed last (left out, it is still searched first), the same
   attacks are refused, and `TriggerFunctionsPinTheirSearchPathTest` runs each of them. A trigger function added later
   has to fix its path too; the same test fails if one does not.

**The limit:** the owner can still `ALTER TABLE … DISABLE TRIGGER` or `DROP TRIGGER`, so
the triggers are not a defense against a malicious or compromised owner. That is why the
owner role is for migrations only and is never what the application runs as. Anyone who
can connect as the owner can rewrite history; guarding that is an operational concern
(credentials, access control, audit), not something these triggers can provide.

**What the as-of balance relies on, and where it stops.** The 60-second window is only sound while a
transaction of `ledger_app` ends after 30 seconds. That is a **default**, not a restriction:
`transaction_timeout` is set on the role by the init script (`ALTER ROLE … SET`, which applies to each new
session; the owner is unbounded, because Flyway needs that), and a role can change its own session settings, so a
`ledger_app` session can `SET transaction_timeout = 0` for itself (`TransactionTimeoutTest` shows it). The window is
also only sound because prepared transactions are disabled (`max_prepared_transactions = 0`, the Postgres
default): a prepared transaction leaves its session and can be committed much later while keeping the timestamp of
its start. The application checks the default and the prepared-transactions setting once, when it starts, and
refuses to run otherwise; it never changes the setting. So the guarantee **assumes a correct application**. It is no
defence against someone who holds `ledger_app`'s credentials: they could open a session without the bound, keep a
transaction open past the window and commit an entry whose `created_at` is older than it (a line can no longer be
added to an entry committed earlier, see 3 above, but a whole new entry can be recorded late). The other limits: an
owner insert with an explicit `created_at` bypasses all of this (the owner is for migrations only); and the
argument assumes a single primary database and a clock that does not jump backwards.

The degenerate-entry rules above are enforced the same two-layer way: the domain rejects
them first with a `422`, and database constraints (`amount_cents <> 0`, one line per
account per entry, and a commit-time check for at least two lines) catch anything that
gets past it. A backstop firing is a bug, so it surfaces as a `500` and is logged.

The reversal rules have database backstops too. `reverses_entry_id` is `UNIQUE` (an entry is reversed at most
once; the one violation a client can provoke, by racing two reversals, is reported as `entry-already-reversed`),
and a deferred trigger checks at commit that a reversal's lines are exactly the original's with each amount negated
(`LDG04`) and that its original is not itself a reversal (`LDG05`, which also rules out a reversal that references
itself and a cyclic link). A reversal built without negation would still balance, so only that trigger catches it.
