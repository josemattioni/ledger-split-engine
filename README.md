# ledger_split_engine

**Double-entry, append-only ledger core for a card split/settlement engine (split and settlement not built yet).**

In a sub-acquirer model for the Brazilian card market, a card transaction is captured, split between acquirer fee,
marketplace commission and seller payable, and settled later on a D+N schedule. This repository is only the ledger core
those steps would build on: accounts, balanced journal entries, idempotent posting, reversals and as-of balances.
Splitting, settlement scheduling and fee calculation are **not built**, and neither are several structural pieces they
would need (see [Scope and known limitations](#scope-and-known-limitations)). (Despite the name, this is not Splitwise-style bill splitting.)
Balances are never stored: an account's balance is the sum of its entry lines, and a mistake is corrected by a new
entry, never an edit.

## Quickstart

Needs JDK 21 and Docker.

```bash
docker compose up -d      # Postgres 17, exposed on port 5433 (not 5432)
./mvnw spring-boot:run    # the API, on http://localhost:8080
```

Or run the application as a container instead: `docker compose --profile app up -d --build --wait`
([docs/local-setup.md](docs/local-setup.md#running-in-a-container)).

Book a R$100.00 card sale at capture: the acquirer will pay R$98.20 (it charges 1.8%) and the seller is owed R$97.50
(the platform charges the seller a 2.5% MDR). Register the four accounts, post the entry, and read the seller's balance.
Debits are positive and credits negative, so the lines sum to zero and a liability reads negative (`-9750`). Run the
entry command twice: the retry returns the same `201` and body, marked `Idempotent-Replayed: true`, and writes nothing.

```bash
api=http://localhost:8080/api/v1; json="Content-Type: application/json"
curl -i -X POST $api/accounts -H "$json" -d '{"code":"acquirer_receivable","type":"ASSET"}'
curl -i -X POST $api/accounts -H "$json" -d '{"code":"acquirer_fee","type":"EXPENSE"}'
curl -i -X POST $api/accounts -H "$json" -d '{"code":"seller_payable:123","owner_ref":"seller-abc-001","type":"LIABILITY"}'
curl -i -X POST $api/accounts -H "$json" -d '{"code":"mdr_revenue","type":"REVENUE"}'
curl -i -X POST $api/journal-entries -H "$json" -H "Idempotency-Key: demo-key-001" \
  -d '{"lines":[{"account_code":"acquirer_receivable","amount_cents":9820},{"account_code":"acquirer_fee","amount_cents":180},
    {"account_code":"seller_payable:123","amount_cents":-9750},{"account_code":"mdr_revenue","amount_cents":-250}]}'
curl -i $api/accounts/seller_payable:123/balance
```

Tests: `./mvnw verify` runs the unit and integration tests (`src/test`, on Testcontainers: Docker must be running, but
`docker compose up` is not needed), then the black-box contract suite (`src/contractTest`). Coming from an earlier
checkout, recreate the database once with `docker compose down -v`; see [docs/local-setup.md](docs/local-setup.md).
CI (`.github/workflows/ci.yml`) runs the same `verify` and builds the container image. The suite has no skipped tests: the 29
requirements no HTTP client can see are in [docs/requirements-below-http.md](docs/requirements-below-http.md), each with the test that covers it.

## API at a glance

| Method and path | What it does | Success |
|---|---|---|
| `POST /api/v1/accounts` | Register an account (`code`, `type`, optional `owner_ref`) | `201` |
| `GET /api/v1/accounts/{code}` | Look an account up by its code | `200` |
| `GET /api/v1/accounts/{code}/balance[?as_of=...]` | The derived balance, now or as of a settled past instant | `200` |
| `POST /api/v1/journal-entries` | Post a balanced entry; needs an `Idempotency-Key` header | `201` (a replay too) |
| `POST /api/v1/journal-entries/{entry_id}/reversal` | Reverse an entry in full; needs an `Idempotency-Key`, no body | `201` |

Every error is `application/problem+json` (exceptions under "Scope and known limitations"), and each `422` rule has its
own problem `type`. What each endpoint accepts and answers, and the order things are checked in, is in
[docs/api-rules.md](docs/api-rules.md). The service's health check, outside the API, is `GET /actuator/health`: just the
status, `UP` or `DOWN` (503).

## Design decisions

- **Invariants twice.** Every rule is checked in the domain first, as a `422` with its own problem `type`, and again in
  the database as a backstop: entries sum to zero, have at least two lines and no zero amount, and a reversal's lines are
  the exact negation of the original's. A backstop that fires is a bug and surfaces as a `500`.
  [Architecture](docs/architecture.md).
- **Idempotent posting that survives races.** A required `Idempotency-Key` and a unique index with
  `INSERT … ON CONFLICT DO NOTHING`: a retry is answered with the original entry, the same key for different lines is a
  `422`, and concurrent requests with one key need no lock. [Posting an entry](docs/api-rules.md#posting-an-entry).
- **A reversal is a new entry, at most once, decided by a constraint.** The original is never touched. `UNIQUE
  (reverses_entry_id)` allows one reversal, and a commit-time trigger checks the negation. A race between a reversal and
  its own retry, found by a test, is handled by asking twice. [Reversing an entry](docs/api-rules.md#reversing-an-entry).
- **As-of balances answer only for settled instants.** An entry's `created_at` is when its transaction started, but it is
  visible only at commit, so an `as_of` less than 60 seconds old (on the database's clock) is a `422`. That rests on a
  30-second transaction bound the application verifies at startup. [Balances](docs/api-rules.md#balances).
- **Fail loud, never normalize.** Unknown JSON fields and query parameters are a `400`, a padded or blank value is
  rejected rather than trimmed, an error never echoes more than 64 characters of what was sent, and a body over 256 KiB
  (`ledger.http.max-body-bytes`) is a `413`. [`PROJECT-CONVENTIONS.md`](PROJECT-CONVENTIONS.md).
- **Specs first, checked by a suite written apart from the code.** Each capability's requirements and scenarios are in
  [`openspec/specs/`](openspec/specs); an agent in a QA role wrote the contract suite from them, without reading the
  source. [How this was built](#how-this-was-built).

## Append-only guarantees and their limits

Ledger rows are never edited or deleted. Three layers, each precise about what it stops:

1. **Privileges.** The application connects as `ledger_app`: `SELECT`, and `INSERT` only on the columns a client
   supplies. No `UPDATE`, `DELETE` or `TRUNCATE` on any table (accounts included), and no DDL, so an id or a timestamp
   cannot be invented.
2. **Triggers.** Row triggers refuse an update or delete, and a statement trigger refuses `TRUNCATE`, even for the
   table owner.
3. **An entry's lines are fixed when it is recorded (`LDG06`).** A line can only be inserted by the transaction that
   inserts its entry, so no line can be added to an entry committed earlier and move a settled as-of balance.

The checks behind these layers read the real tables whatever the session's search path is, so a session that shadows a
ledger table with a temporary one cannot make a check pass.

**The limits:** the owner can still disable or drop the triggers, which is why the owner role is for migrations only.
And the as-of guarantee rests on a transaction bound that is a role *default* the application keeps, not a restriction,
so it assumes a correct application. Full account: [docs/ledger-guarantees.md](docs/ledger-guarantees.md).

## Scope and known limitations

**Not built:** payment splitting, D+N settlement scheduling and fee calculation; authentication and authorization
(every endpoint is open); reading an entry back by its `entry_id`; listing or paging entries; account statements; an
account status (a mistyped account stays open, see
[Correcting an account](docs/api-rules.md#correcting-an-account)); and more than one currency (BRL only, by design;
`PROJECT-CONVENTIONS.md` says what a second would take).

**Structural gaps for card split and settlement** are in the data model, not only in missing endpoints: an entry has no
metadata (event type, external reference, actor); only the recording time is kept, with no effective or settlement
date; an account appears once per entry, so installments do not fit; nothing limits a balance or serializes a payout;
a reversal is total, once and final, with no dispute lifecycle; and idempotency keys are global and permanent. Each one,
and the first changes to close it: [docs/scope-and-limits.md](docs/scope-and-limits.md#structural-gaps-for-card-split-and-settlement).

**Limits of what is built:**

- **A balance is the sum of the account's lines, so a read costs time proportional to them.** Measured with 2M lines on
  one account (platform accounts such as fees accumulate a line per sale): about 0.3 s for the current balance and 1.7 s
  for an as-of balance, whatever the instant asked; a seller with 2k lines takes milliseconds. A read that passes the
  30-second transaction bound loses its connection and answers `500`. Extrapolated, that is about 35M lines on one
  account for the as-of read. Split a hot account by period in its code (`acquirer_fee:2026-10`). What would scale, and
  what each costs: [docs/scope-and-limits.md](docs/scope-and-limits.md#balance-reads-and-scale).
- A request the HTTP server rejects before the application sees it gets Tomcat's own HTML error page, not problem+json:
  a control character in the `Idempotency-Key` header, a non-ASCII byte in the request line, or an invalid path
  (`%FF`, `%00`, an encoded `..`). Nothing is created. A valve that would make these problem+json was prototyped and
  left out; the specs record the exception.
- A trigger that compares transaction ids cannot see through a savepoint, so a header inserted inside one is refused with
  `LDG06`. Nothing in the application opens one.
- The `owner_ref` padding rule is Unicode-aware in the application but not in the database (`trim()` only catches
  ordinary spaces). The application is the only writer, so the domain check is the control.
- The credentials in `application.yaml` and `docker-compose.yml` are for local development only.

## How this was built

The code, tests and specs were written by AI coding agents; the scope, the design decisions and the acceptance of every
change were the owner's. Each slice is an [OpenSpec](openspec) change (proposal, delta specs, design, tasks), archived
in `openspec/changes/archive/`. Three agent roles: **Developer** (Claude Code) wrote the application and `src/test`;
**QA** wrote the contract suite from the specs and conventions alone, and a failing contract test is reported, never
edited away; **Reviewer** reported defects, and several follow-up changes came from its reports.
More, including the calls that were the owner's: [docs/how-this-was-built.md](docs/how-this-was-built.md).

## More

[docs/architecture.md](docs/architecture.md) (layers, where things are, the design of each slice),
[docs/api-rules.md](docs/api-rules.md), [docs/ledger-guarantees.md](docs/ledger-guarantees.md),
[docs/scope-and-limits.md](docs/scope-and-limits.md), [docs/local-setup.md](docs/local-setup.md),
[`PROJECT-CONVENTIONS.md`](PROJECT-CONVENTIONS.md). Licensed under the [MIT License](LICENSE).
