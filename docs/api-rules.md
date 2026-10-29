# API rules and examples

What each endpoint accepts and answers, with a `curl` example where one helps. The rules that hold on every
endpoint (JSON shape, strict parsing, error format, query parameters) are in
[`PROJECT-CONVENTIONS.md`](../PROJECT-CONVENTIONS.md); what each capability promises, scenario by scenario, is in
[`openspec/specs/`](../openspec/specs). The examples assume the application is running (see the
[README](../README.md#quickstart)) and use the same four accounts and entry as its quickstart; if you already ran that,
registering an account again is a `409`, and posting the entry again is a replay.

## Accounts

Register an account:

```bash
curl -i -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"code":"seller_payable:123","owner_ref":"seller-abc-001","type":"LIABILITY"}'
```

Register the other three accounts the example entry below uses (an entry needs at least two, on different accounts):

```bash
curl -i -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"code":"acquirer_receivable","type":"ASSET"}'
curl -i -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"code":"acquirer_fee","type":"EXPENSE"}'
curl -i -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"code":"mdr_revenue","type":"REVENUE"}'
```

Fetch the first one back by its code:

```bash
curl -i http://localhost:8080/api/v1/accounts/seller_payable:123
```

An account's `code` is its natural key — lowercase snake_case, optionally followed by
`:identifier` (e.g. `marketplace_commission`, `seller_payable:123`, `acquirer_fee`).
`type` is one of `ASSET`, `LIABILITY`, `REVENUE`, `EXPENSE` — the accounting
classification, not the account's purpose (purpose lives in `code`) — and must be written
as that name: a position in the list, whether the number `0` or the string `"0"`, is
rejected. `owner_ref` is optional and opaque. `currency` is always `BRL` and is not
accepted in the request body. Request and response fields are snake_case, matching the
database columns.

Errors are returned as `application/problem+json` — a malformed `code`, an invalid
`type`, an unrecognized field, a field given twice, a blank/padded `owner_ref`, or a lookup
by an unknown `code`, all return `400`/`404` as appropriate; a duplicate `code` returns `409`.
The `400` says what to fix: an unrecognized field is named (`unrecognized field "nickname"`), and
a `type` that is not exactly one of the four names, padded (`" ASSET"`) or differently cased
included, lists them (`type must be exactly one of ASSET, LIABILITY, REVENUE, EXPENSE`) without
repeating what was sent, and an error never repeats more than the first 64 characters of a value a client sent (a
200,000-character `owner_ref` is a short `400`, not a 200 KB one). A required field that is missing, null or blank is named too
(`code must not be blank; type must not be null`; inside an entry, `lines[1].amount_cents must not be null`). A value is
never trimmed into a valid one: an `owner_ref` padded with any Unicode whitespace, a non-breaking space included, is
refused like one padded with ordinary spaces, and so is one the database could not keep exactly as sent (a NUL character
or an unpaired surrogate), rather than failing with a `500` or being stored as something else.

**Retrying a registration.** Registering an account is not idempotent, on purpose: a second `POST` with a `code` that
exists is a `409`, whether or not the body is the same, and nothing is created. Idempotency is for what moves money (a
retried entry would post twice); a retried registration cannot do harm, and the `409` names the code. After a timeout,
fetch the account by its code, as above. A `404` means the first request did not get through, so register again. A `200`
means it did, or that someone else holds the code: an account never changes, so what you read is what the first
request created, and comparing its `type` and `owner_ref` with yours tells you whether it is the account you meant.

## Posting an entry

Post the entry that books a R$100.00 card sale at capture, across the four accounts above. The acquirer will pay R$98.20
(it charges the platform 1.8%, R$1.80) and the seller is owed R$97.50 (the platform charges the seller a 2.5% MDR, R$2.50):

| Account | Type | `amount_cents` | Side |
|---|---|---|---|
| `acquirer_receivable` | ASSET | `9820` | debit: what the acquirer will pay out |
| `acquirer_fee` | EXPENSE | `180` | debit: the acquirer's cost |
| `seller_payable:123` | LIABILITY | `-9750` | credit: what the platform owes the seller |
| `mdr_revenue` | REVENUE | `-250` | credit: the platform's commission |

The lines sum to zero, and the platform's margin on the sale is the MDR less the acquirer's cost (250 - 180 = R$0.70).

```bash
curl -i -X POST http://localhost:8080/api/v1/journal-entries \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-key-001" \
  -d '{"lines":[
    {"account_code":"acquirer_receivable","amount_cents":9820},
    {"account_code":"acquirer_fee","amount_cents":180},
    {"account_code":"seller_payable:123","amount_cents":-9750},
    {"account_code":"mdr_revenue","amount_cents":-250}]}'
```

Send the exact same request again. The post is idempotent: you get the same `201` and the
same body (same `entry_id`, same `created_at`) back, plus an `Idempotent-Replayed: true`
header, and nothing is written a second time.

`lines` is a list of `{account_code, amount_cents}` pairs that must sum to exactly
zero — `amount_cents` is a signed integer, debit positive, credit negative, and at most
10^12 centavos (10 billion BRL) in absolute value per line. An entry is always a real
movement of money: at least two lines, no zero `amount_cents`, and no `account_code` on
more than one line (a debit and a credit on the same account are rejected, not netted).
Values must arrive as the
JSON type shown: a string `"-100"` is rejected, not converted. A property that appears
twice in one JSON object (`"amount_cents":0,"amount_cents":-250`) is rejected with `400`
rather than resolved to one of its values; this holds on every endpoint. `balance_cents`
in the balance response is the raw signed sum of an account's entry lines, never
normalized by account type — a credit-normal account (e.g. a payable) can show a negative
balance.

**Two more limits on an entry.** It has **at most 100 lines**. The capture split above takes
four lines; a cart with several sellers adds one payable line per seller (the receivable, the fee and the
commission stay one line each, since an account appears once per entry), so 100 covers up to 97 sellers in one entry. The limit also bounds the work done per entry: the
account lookup and the balance check at commit both grow with the number of lines. And
every line's `account_code` must have the same shape as a registered account's code
(lowercase snake_case, optionally followed by `:identifier`, at most 100 characters) and is
compared exactly: `ACQUIRER_FEE` or `" acquirer_fee"` is rejected, never trimmed or
lower-cased onto the real account. A malformed code is `422 invalid-account-code`; a
well-formed code that names no account is `422 unknown-account`. The same malformed code at
account registration is a `400`, and the difference is deliberate: there the code is the
field being created, so a bad one is a malformed field; on a journal line the JSON is fine
and the code simply names nothing that could exist, which makes it a ledger rule.

**Retrying a post.** The `Idempotency-Key` header is required exactly once (1–255 visible
ASCII characters) and makes the post safe to retry:

- The same key with the same lines returns the original `201` again, with an identical body
  and the header `Idempotent-Replayed: true`. Nothing is written. The header is present on
  replays only.
- The same key with different lines returns `422` (`idempotency-key-reused`). The `detail`
  names the key and does not repeat the stored entry. "The same lines" means the same
  account and amount at every position, so the same lines in a different order are a
  different request.
- Keys never expire, and only a successful post uses one up: a request that was rejected
  stored nothing, so it can be retried with the same key.

Use a natural business key, for example the sale id, and not a fresh UUID per attempt. A new
key on every retry looks like a new request, so a retry after a timeout would post the entry
a second time and defeat the protection.

## Balances

Read a balance back:

```bash
curl -i http://localhost:8080/api/v1/accounts/seller_payable:123/balance
```

The response is `{"account_code", "currency", "balance_cents", "as_of"}`; `as_of` is `null` for the current
balance (the key is always there). After the entry above, `seller_payable:123` reads `-9750`: a liability is credited
when the platform comes to owe more, and the balance is the raw signed sum. A balance is computed from the account's lines on every read, so its cost grows with
them; see [Balance reads and scale](scope-and-limits.md#balance-reads-and-scale).

**Reading a balance as of a past moment.** Add `as_of` to get the sum of the account's lines whose entry was
recorded (`created_at`) at or before that instant, inclusive:

```bash
curl -i "http://localhost:8080/api/v1/accounts/seller_payable:123/balance?as_of=2026-10-21T15:00:00Z"

# A positive offset: write the + as %2B. A literal + in a query string decodes to a space, which is a 400.
curl -i "http://localhost:8080/api/v1/accounts/seller_payable:123/balance?as_of=2026-10-21T18:00:00%2B03:00"

# A negative offset needs no encoding.
curl -i "http://localhost:8080/api/v1/accounts/seller_payable:123/balance?as_of=2026-10-21T12:00:00-03:00"
```

`as_of` is an RFC 3339 date-time with an explicit offset (`Z` or `±hh:mm`): uppercase `T` and `Z`, seconds
required, and a fraction of at most 6 digits (a microsecond, the precision the database keeps). It is echoed back in
UTC, so the three requests above are the same instant and get the same answer. An account that did not exist yet at
that instant answers `200` with `0`, not `404`. Time is ordered by when an entry was recorded, never by its internal id.

**Why a recent instant is refused.** An entry's `created_at` is the moment its transaction *started*, but the entry only
becomes visible when the transaction *commits*, so a balance as of a moment that is too recent could still change after
it was read. The system therefore answers only for an `as_of` at least 60 seconds before the database's own clock
(never the application's); anything later, a future instant included, is `422`
(`urn:ledger-split-engine:problem:as-of-not-settled`, and the `detail` states the window). The guarantee rests on a default
of 30 seconds (half the window) that the database gives every new session of `ledger_app` for how long a transaction may
last. The application checks the default when it starts and never changes it; it is a default and not a restriction, so the
guarantee assumes a correct application (see [Append-only guarantees and their limits](ledger-guarantees.md)).

**Any other query parameter is rejected** with a `400` naming it, on this endpoint and on every other one. `?asOf=...` and
`?AS_OF=...` are not misspellings that are quietly forgiven: ignoring them would answer a request for a past balance with
the current one and report success, the same mistake as ignoring an unknown JSON property. Only the balance read takes a
parameter at all, and only `as_of`: `POST /api/v1/accounts?x=1`, `GET /api/v1/accounts/{code}?as_of=...` and the other
endpoints with any query string are a `400` too, and nothing is written. Only the query string counts: the fields of a
form-encoded request body are never reported as query parameters. A malformed percent-encoding anywhere in the query
string is a `400` as well, with the detail "the query string is not valid percent-encoded UTF-8", and a parameter with no
name (`?=1`) with "the query string has a parameter with no name". Errors are evaluated in
this order: a malformed `as_of` or an unknown parameter (`400`), an unknown account (`404`), an unsettled instant (`422`).

## Errors and the order things are checked in

A request that's well-formed but violates a ledger rule — doesn't balance, is degenerate
(fewer than two lines, a zero amount, or a repeated account), has more than 100 lines,
names a malformed account code, exceeds the per-line amount limit, references an unknown
account, reuses an idempotency key for different lines, asks to reverse an entry that is
itself a reversal or that is already reversed, or (once multi-currency exists)
spans more than one currency — returns `422`, not `400`; `400` is reserved for requests
the client got wrong (missing fields, wrong types, a property given twice, a missing or
invalid `Idempotency-Key`). A failure inside the system is never reported as a `400`: it is
a `500` with a generic message, and the details are logged. One kind of `400` is not
problem+json: a request the HTTP server itself rejects before the application sees it, such as an
`Idempotency-Key` containing a control character (DEL or `0x01`), gets Tomcat's own error page
and nothing is created. Each 422 rule has its own
problem `type`, so the violated rule is identifiable from more than the status:

| Rule | `type` |
|---|---|
| Entry does not balance | `urn:ledger-split-engine:problem:unbalanced-entry` |
| Degenerate entry (the `detail` says which case) | `urn:ledger-split-engine:problem:degenerate-entry` |
| More than 100 lines | `urn:ledger-split-engine:problem:line-limit-exceeded` |
| Malformed account code on a line | `urn:ledger-split-engine:problem:invalid-account-code` |
| Unknown account (a well-formed code that matches no account) | `urn:ledger-split-engine:problem:unknown-account` |
| Mixed currencies | `urn:ledger-split-engine:problem:mixed-currency` |
| Amount outside the permitted range | `urn:ledger-split-engine:problem:amount-limit-exceeded` |
| Idempotency key already used for different lines | `urn:ledger-split-engine:problem:idempotency-key-reused` |
| The entry to reverse is itself a reversal | `urn:ledger-split-engine:problem:cannot-reverse-a-reversal` |
| The entry to reverse has already been reversed | `urn:ledger-split-engine:problem:entry-already-reversed` |
| A balance as of an instant less than 60 seconds old, or in the future | `urn:ledger-split-engine:problem:as-of-not-settled` |

A post is evaluated in this order. First the request must be well-formed (`400`) and satisfy
every rule that needs no stored data: shape, degenerate entry, line limit, account-code
format, amount limit and balance (`422`). Second, the idempotency key is looked up, and a
retry is answered from the stored entry. Third, and only for a key not yet used, the accounts
must exist and share one currency. So an invalid request gets its own error whatever its key,
and a retry never looks at accounts again: a reused key that also names an unknown account
reports `idempotency-key-reused`, while a reused key that names a malformed code reports
`invalid-account-code`.

## Reversing an entry

The ledger is append-only, so a mistaken entry is never edited or deleted. It is corrected by a
**reversal**: a new entry with the original's lines (same `line_number`, same `account_code`) and each
amount negated, linked back to the original. Both stay in the history, and afterwards every affected
account is back to the balance it had before the original.

```bash
curl -i -X POST http://localhost:8080/api/v1/journal-entries/<entry_id>/reversal \
  -H "Idempotency-Key: reversal-of-<entry_id>"
```

- `<entry_id>` is the original's `entry_id`, written as a UUID in its canonical form (8-4-4-4-12
  hexadecimal digits, either letter case). Anything else is a `400` and is never reshaped: `1-1-1-1-1` is
  refused, not read as a UUID. A malformed or missing `Idempotency-Key` is a `400` too, and an `entry_id`
  that matches no entry is a `404`.
- There is **no request body**, and one is rejected with `400`. The server builds the lines. A body that
  was ignored would answer a client that tried to send lines (a partial reversal) with a full one. A
  partial correction is an ordinary new entry.
- The answer is `201` with the reversal in the same representation as any entry. **Every entry the API
  returns now carries `reverses_entry_id`**: `null` for an ordinary entry (an additive change to the
  `POST /journal-entries` response), and for a reversal the `entry_id` of the entry it reverses.
- An entry can be reversed **once**: a second reversal under a new key is `422 entry-already-reversed`, and
  its detail names the original. A reversal **cannot itself be reversed** (`422 cannot-reverse-a-reversal`).
  To undo a reversal, post the original movement again as an ordinary new entry.
- Otherwise a reversal is an entry like any other: its own `entry_id`, `created_at` and idempotency key, and
  it counts in balances.
- **Retries.** Derive the key from the entry, for example `reversal-of-<entry_id>`. Then a retry after a lost
  response is the same request and is answered with the same reversal (`201` plus `Idempotent-Replayed:
  true`). A fresh key per attempt is a different request: the system cannot tell it from a second reversal, so
  it is `entry-already-reversed`, even though the first reversal succeeded. The same key for a different entry,
  or a key that an ordinary entry already used, is `422 idempotency-key-reused`.
- **Order.** Path and key are checked first (`400`), then that the entry exists (`404`), then that it is not
  itself a reversal (`422`), then the key (replay or reuse), then that the entry has not already been reversed
  (`422`). So a reused key reports the reuse, even for an entry that is also already reversed, and a retry of a
  successful reversal replays instead of being refused.
- **Concurrency.** Simultaneous reversals of one entry produce exactly one reversal, decided by a database
  constraint rather than a lock: under different keys one succeeds and the other is `entry-already-reversed`;
  under the same key both receive the same reversal, one of them marked as a replay.

## Correcting an account

Accounts are immutable: once registered, an account's `code`, `owner_ref` and `type` cannot be changed, not by the
application and not by the table owner. So a mistyped one is corrected by registering the right account and
moving the balance with an entry. For example, `seler_payable:123` was registered by mistake and entry `E` (a credit of
9750) was posted to it instead of `seller_payable:123`:

```bash
# 1. Register the account that should have been used.
curl -i -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"code":"seller_payable:123","type":"LIABILITY"}'

# 2. Reverse the mistaken entry E, then post the same movement again against the right account.
curl -i -X POST http://localhost:8080/api/v1/journal-entries/<E's entry_id>/reversal \
  -H "Idempotency-Key: reversal-of-<E's entry_id>"
curl -i -X POST http://localhost:8080/api/v1/journal-entries \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-key-001-corrected" \
  -d '{"lines":[
    {"account_code":"acquirer_receivable","amount_cents":9820},
    {"account_code":"acquirer_fee","amount_cents":180},
    {"account_code":"seller_payable:123","amount_cents":-9750},
    {"account_code":"mdr_revenue","amount_cents":-250}]}'
```

(Or, without touching `E`, post one entry that moves the balance: `+9750` on `seler_payable:123` and `-9750` on
`seller_payable:123`.) Renaming in place is not offered because it would break idempotent replay: a stored entry reloads
with its account's *current* code, so after a rename, a retry of an old request, which still names the old code, would no longer
match and would be answered `422` instead of with its original response.

**The known gap:** there is no account status, so the mistyped account stays open and can still receive postings. Closing
or deactivating an account is not built.
