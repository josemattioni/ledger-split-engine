# API Conventions

These apply to every endpoint in every slice.

- JSON is snake_case in both directions (`owner_ref`, `created_at`).
  Java DTOs are camelCase; Jackson bridges them globally.
- Unknown JSON properties are rejected with 400. Never silently ignored.
- A 400 for a body that cannot be read says what the client can fix when it can
  do so without replaying the input: an unrecognized field is named (`unrecognized
  field "nickname"`), and an enum value that is not one of the names is `<field>
  must be exactly one of ASSET, LIABILITY, ...`, never the value that was sent.
  `ApiExceptionHandler` does this from Jackson's exception types; every other
  cause (malformed JSON, a wrong JSON type, a field given twice) keeps Spring's
  generic "Failed to read request".
- An error detail repeats at most 64 characters of a value the client sent: a
  longer one is cut and marked with an ellipsis (`EchoedValue`, in the domain, so
  the domain's own messages use it; it counts code points and never splits a
  surrogate pair). A 200,000-character `owner_ref` must not come back as a 200 KB
  response. Values of 64 characters or fewer stay whole, so a short value is still
  named. The one echo that is not cut is the detail of a key reused for a
  different request: that key already passed its shape check, so it is at most 255
  characters, and it is named in full.
- A request body is read at most up to `ledger.http.max-body-bytes` (256 KiB by
  default; the largest legitimate body, an entry of 100 lines, is about 10 KB).
  `RequestBodyLimitFilter` counts the bytes actually read, so it holds for a
  chunked body that declares no length, and a request whose handler never reads a
  body is not affected. Past the limit the read fails and `ApiExceptionHandler`
  answers 413 problem+json; nothing is written and no key is consumed. Tomcat has
  no such limit for JSON (`maxPostSize` is for forms).
- The body limit applies to every request and runs first. It does not decide by
  path: Spring picks a handler on the decoded path with `;` parameters removed
  (`/api;x=1/v1/accounts` and `/%61pi/v1/accounts` reach the same handler as
  `/api/v1/accounts`), so any rule that starts from "paths that look like the
  API's" can be out of step with it. It also runs ahead of Spring Boot's
  form-content filter, which reads a form-encoded `PUT`, `PATCH` or `DELETE` body
  in full before the request reaches the handler that answers 405. A check on
  what a handler reads (a reversal reads one byte, to know there is a body) keeps
  this true without reading more than it needs.
- A required field that is missing, null or blank is a 400 that names it, in the
  request's own snake_case: `code must not be blank; type must not be null`,
  `lines[1].amount_cents must not be null`. The wording is fixed per constraint
  (never Bean Validation's localized message, never the rejected value), sorted so
  it does not depend on validation order, and cut at ten entries with a count
  (`; and 15 more`), because a request can break the rule on every element of an
  array. A new request DTO needs no code for this, only its constraints.
- Free text that reaches the database must be text the database can keep exactly
  as sent. PostgreSQL text cannot hold U+0000 (the insert fails: a 500 for a
  client's mistake), and the driver replaces an unpaired surrogate with `?` (an
  altered value stored without a word). `Account` refuses both in `owner_ref`;
  `owner_ref` is the only free-text field, every other client-supplied string has a
  restricted shape. A new free-text field needs the same check. Lengths are in
  characters (code points), as the spec says and as `VARCHAR` counts: 100 emoji
  are 100 characters, not 200 UTF-16 units.
- An enum in a request body is read by its exact name. Jackson retries a
  failed enum lookup with the text trimmed and has no switch for it, so `" ASSET"`
  would be stored as `ASSET`. Every enum a request can carry is therefore
  registered with `StrictEnumDeserializer` in `JacksonConfig.strictEnums()`; a new
  one that is not registered is lenient again.
- "Padded" means Unicode `White_Space` at either end of a value (no-break space,
  em space, ideographic space and the rest), not what `String.trim()` strips,
  which stops at U+0020. A value with such a space inside is fine.
- Unknown query parameters are rejected with 400, naming them, for the same
  reason: a silently dropped input is a false success. A misspelled `as_of`
  would otherwise answer a request for a past balance with the current one.
  Names match exactly (`asOf` and `AS_OF` are unknown). This holds on every
  endpoint, including the ones that read no parameter at all (`POST
  /accounts?x=1` is a 400, and nothing is registered).
- It is enforced in one place, not per controller: `QueryParameterInterceptor`
  runs before every handler under `/api/v1` and compares the request's
  parameter names with `@AcceptedQueryParameters` on the handler method. No
  annotation means no parameter is accepted, so a new endpoint is strict by
  default and one that starts to read a parameter must declare it. Nothing is
  examined when the request has no query string.
- Query parameters are read from the query string alone, by the application
  (`QueryString`), never from `getParameterMap()`: with a query string present
  the container's map also parses a form-encoded POST body, so `POST
  /accounts?x=1` with a body `code=a&type=ASSET` came out as three "query
  parameters" and Tomcat read up to 2 MB of the body to say so. A controller that
  reads a declared parameter takes it from `QueryString`, as `AsOfParameter`
  does. `getParameterMap()` and `getParameter()` are not used in `src/main`.
- A query string that cannot be read is a 400 with one fixed detail that repeats
  nothing the client sent. A malformed percent-encoding (`%zz`, a lone `%`, an
  invalid UTF-8 sequence, decoded strictly: `URLDecoder` would substitute a
  replacement character) is "the query string is not valid percent-encoded
  UTF-8". A parameter with a value and no name (`?=1`) is "the query string has a
  parameter with no name". Because the application decodes the query string
  itself, `MockMvc` exercises it for real; its `.param()` still sets no query
  string, so put the parameters in the URL when a test is about this rule. Only
  what Tomcat itself refuses before the application sees it (raw non-ASCII in the
  request line, an invalid path) needs a socket.
- A JSON property repeated within one object is rejected with 400, like an
  unknown one. It is never resolved to the first or the last value. An enum
  value must be one of its names: a string holding a number is not a name.
- A 400 is only for a request the client got wrong: a malformed body, or a
  structural defect found while building domain objects from the request. A
  controller wraps exactly that construction in
  `InvalidRequestException.guard(...)`. Every other failure, including an
  `IllegalArgumentException` raised by a use case, a port or an adapter, is
  the generic 500 with an ERROR log, because there it is the system's fault.
  A new controller that builds domain objects without the wrapper would turn
  a client mistake into a 500.
- All error responses are `application/problem+json`, including 404.
  No endpoint returns a bare status with no body. The one exception is a
  request Tomcat rejects before the application sees it (for example a
  control character in the `Idempotency-Key` header, or a path with an invalid
  percent-encoding): it gets Tomcat's own `400` page and nothing is created. No
  `@ExceptionHandler` can change that. The other is the health endpoint's own
  answer (last bullet of this section).
- Internal database ids never appear in any response. `code` is the
  external identifier.
- Money is signed integer centavos. Never a decimal, never a float.
- Account balances are the raw signed sum of their entry lines — debit
  positive, credit negative — never normalized by account type.
- Each 422 ledger-rule violation carries its own problem `type`
  (`urn:ledger-split-engine:problem:<rule>`), so the violated rule is
  identifiable from more than the status code. Other statuses keep the
  default `type`.
- All API endpoints are under `/api/v1`.
- Operational endpoints are not API endpoints, and `/actuator/health` is the only
  one (spec `service-health`, `HealthEndpointTest`). It lives outside `/api/v1`;
  its answer is the framework's health document, `{"status":"UP"}` with `200` or
  `{"status":"DOWN"}` with `503`, with no detail, and not `application/problem+json`;
  and the rules above that belong to the API (the error format, the unknown
  query parameter rule, which `QueryParameterInterceptor` applies under `/api/v1`
  only) do not apply to it. Every other path under `/actuator` is a `404`, and
  discovery, details and the liveness and readiness groups are off. Its database
  check is `DatabaseHealthIndicator`, not Boot's: Boot's hangs on a database that is
  frozen rather than refusing connections (the request thread waits in a socket
  read that has no timeout), so Boot's is off and `HealthEndpointTest` checks it.
  A new operational endpoint needs a change that updates this bullet and the spec.

# Database and Ledger Conventions

These apply to every migration and every slice that touches the ledger.

- The app connects as `ledger_app`: `SELECT` on every table, and `INSERT` only
  on the columns a client supplies (accounts: `code`, `owner_ref`, `type`;
  journal entries: `idempotency_key`, `reverses_entry_id`; entry lines:
  `journal_entry_id`, `line_number`, `account_id`, `amount_cents`). Migrations
  run as the owner. Server-assigned columns (`id`, `entry_id`, `created_at`,
  `currency`) are never insertable by `ledger_app`, `OVERRIDING SYSTEM VALUE`
  included, so they only ever come from their defaults. A new table gets
  `SELECT` automatically and `INSERT` only through an explicit column-level
  grant, on named columns, in the migration that creates it.
- Accounts are immutable. Like journal entries and entry lines, an account
  cannot be updated, deleted or truncated, even by the owner (`LDG02`). A
  mistyped `code`, `owner_ref` or `type` is corrected by registering the right
  account and moving the balance with an entry; renaming in place would break
  idempotent replay, because stored entries reload with the account's current
  code. There is no account status, so a mistyped account stays open.
- Time ordering uses `created_at`, never `id`. Since slice 5 a reversal can have
  a lower id than its original (only through `OVERRIDING SYSTEM VALUE`, which
  is now owner-only); nothing may order by id.
- As-of balances are inclusive and answered only for an `as_of` at least
  `SETTLE_WINDOW` (60 s) before the database's own clock, never the JVM's: an
  entry's `created_at` is the start of its transaction, but it becomes visible
  at commit. That holds while a `ledger_app` transaction ends after
  `transaction_timeout` (30 s, half the window) and prepared transactions are
  disabled (`max_prepared_transactions = 0`). The 30 s is a role default set by
  the init script (`ALTER ROLE … SET`), not a restriction: a session of the role
  can `SET transaction_timeout = 0` for itself (`TransactionTimeoutTest` shows
  it), so the guarantee assumes the application never does and is no defence
  against whoever holds the role's credentials. The application refuses to start
  if the default is missing, disabled or too large, or if prepared transactions
  are enabled (`TransactionBoundGuard`, checked once, at startup). The sum runs
  as a statement of its own after the clock was read, never inside a transaction
  (`Propagation.NEVER`).
- The ledger is single-currency by design: `accounts.currency` is
  `CHECK (currency IN ('BRL'))`, so a currency-grouped balance trigger could
  never see two currencies and could not be tested against real data. A second
  currency would take: a new `Currency` value and a widened `CHECK`; `INSERT
  (currency)` granted on `accounts` and named in the adapter again (the
  round-trip test over `Currency.values()` fails until it is); `LDG01` summing
  per currency; after which the existing mixed-currency check becomes
  reachable.
- Ledger tables are append-only. A correction is a new entry, never an
  UPDATE. No later slice may design anything that updates a row: for
  example, reversal links the new entry to the original via
  `reverses_entry_id`; it never marks the original.
- An entry's lines are fixed when the entry is recorded. A trigger on
  `entry_lines` (`LDG06`, V6) refuses a line unless its entry's header was
  inserted by the same transaction, for the application and the owner alike:
  `ledger_app`'s `INSERT` grant covers any `journal_entry_id` and the balance
  trigger only checks a sum, so two lines that cancel out could otherwise be
  added to an entry committed days ago, changing every balance as of any instant
  after its `created_at` and leaving its reversal no longer its exact negation.
  There is no domain operation behind it (nothing adds a line later), so it is a
  backstop with a raw-row test and no domain twin. It compares the header's
  `xmin` with the transaction's xid, which has three consequences: a header inserted
  inside a savepoint is not recognised (no `NESTED` propagation, no pgjdbc
  `autosave`, no PL/pgSQL `EXCEPTION` block around an entry insert), the
  comparison has 32 bits (it could be fooled only after the transaction counter
  wraps around, 2^32 transactions), and a restore must keep an entry's header and
  lines in one transaction (`pg_restore --data-only --single-transaction`; a full
  dump restores as it is; `RestoreRecipeTest` runs it).
- Every trigger function fixes its own search path: `SET search_path = public,
  pg_temp` in the `CREATE FUNCTION` of the migration that creates it (V7 did it
  for the five that existed). Without it a function runs with the calling
  session's path; `ledger_app` can create temporary tables and Postgres searches
  the temporary schema first, so a session could shadow a ledger table and make a
  check read the shadow and pass (reproduced for `LDG01`, `LDG03` and `LDG06`),
  and `pg_restore`'s empty path could not resolve the names. `pg_temp` is listed,
  and last, on purpose: left out, it is still searched first.
  `TriggerFunctionsPinTheirSearchPathTest` fails for any trigger function in
  `public` that does not.
- Reversal is the only correction mechanism. It is full (the reversal has
  the original's lines, same `line_number` and `account_code`, each amount
  negated), at most once per entry, and never of a reversal. The server
  builds a reversal's lines; the client never sends them. A partial
  correction is an ordinary new entry, and so is undoing a reversal: post
  the original movement again.
- Each rule that the domain enforces has a database backstop with its own
  SQLSTATE, and a test that inserts raw rows to prove it. The reversal rules
  follow suit: `UNIQUE` on `reverses_entry_id` (at most once), `LDG04` (lines
  are not the exact negation) and `LDG05` (the original is itself a
  reversal, which also rules out a self-referencing or cyclic link).

# Application Layer Conventions

- Writes go through a use case in `application`. A controller calls a use
  case for a write and for a read that applies a ledger rule (the as-of
  balance applies the settle window), and a port directly for a plain read.
- Ports report state, use cases make decisions, the domain stays pure.
  `JournalEntryPort.insertOrFind` says whether the idempotency key was new;
  `PostJournalEntry` decides whether an existing key is a retry or a reuse;
  neither the adapter nor the controller decides.
- Spring is allowed in `application` and the adapters, never in `domain`.
- A use case is not `@Transactional` by default. The adapter method that
  performs the write owns the transaction, and pins any isolation level its
  correctness depends on.
- Comments say why, in a few lines: the trap, the invariant, the constraint that
  is not visible in the code. How a decision was reached (which slice, what was
  observed in which round, the alternatives) belongs in the change's `design.md`,
  and a comment points to it or to a document under `docs/` rather than telling
  it. A comment that would go stale when the next change lands, or that repeats
  what this file or a spec says, is a pointer and not a copy.

# Idempotency Contract

This is the contract for every write that takes an `Idempotency-Key`.

- Same key and same payload: the original `201` again, with an identical body
  (same ids, same `created_at`) and the header `Idempotent-Replayed: true`.
  Nothing is written. The header appears on replays only.
- Same key and a different payload: `422` with the problem type
  `urn:ledger-split-engine:problem:idempotency-key-reused`. The `detail`
  names the key and never echoes the stored payload.
- Keys never expire: a key lives as long as the entry it identifies.
- Only a successful write consumes a key. A request that fails (400, 422 or
  500) stores nothing, so the same key can be used again.
- A request is validated before its key is looked up, so an invalid request is
  rejected on its own merits whatever its key.
- "Same payload" is compared in the domain by `sameRequestAs`: the lines and,
  for a reversal, the entry being reversed. Every request field that
  determines what is stored takes part in that comparison and is persisted,
  or a retry with a different value would replay as if identical. Identical
  lines are not enough: an ordinary entry that carries a reversal's lines is
  not that reversal.
- A retry can arrive while the request it repeats is still in flight. It must
  be answered as if it had arrived afterwards, whichever database constraint
  happens to object first (a use case that sees a constraint violation for a
  request that carries a key asks once more, so the key's own check decides).
