## Why

All six slices are implemented and archived, and a final review of the whole project found eleven gaps before the first commit. Two are real holes: the request-body limit can be bypassed by other spellings of an API path, and the application's database role can append lines to an entry committed earlier, which would rewrite a settled as-of balance under an old `created_at` (the very thing V5 exists to prevent). Four more are rules that over- or under-promise, and four are small structure problems. Nothing is committed yet, so this is the cheapest moment to close them in one pass.

## What Changes

Behavior changes (numbered as in the review; 1 and 2 were reproduced against the running app and database, the rest come from reading the code):

- **1. The body limit applies to every request.** `RequestBodyLimitFilter` decided by comparing the raw `getRequestURI()` with `/api/v1/`, but Spring matches handlers on the decoded path with `;` parameters removed. Reproduced: 300 KB to `/api/v1/accounts` is a 413, while the same body to `/api;x=1/v1/accounts`, `/api/%761/accounts` or `/%61pi/v1/accounts` is parsed in full (400), and a 2 MB body to `/api;x/v1/journal-entries/{id}/reversal` is a 400. The filter stops deciding by path at all: it counts the bytes read on every request, so there is no spelling left to get wrong. Grounding the fix turned up a second route to the same hole: Spring Boot's form-content filter (order -9900) reads a `PUT`/`PATCH`/`DELETE` form body in full before a default-order filter wraps the stream, so the limit filter also moves ahead of it (a failing test first confirms it).
- **2. A line can only be added in the transaction that inserted its entry (V6, `LDG06`).** `ledger_app`'s `INSERT` on `entry_lines` covers any `journal_entry_id`, and the balance trigger checks only the sum. Reproduced as `ledger_app`: two lines (+5000/-5000) inserted into a four-day-old entry, then `SET CONSTRAINTS ALL IMMEDIATE`, no error. A new `BEFORE INSERT` trigger on `entry_lines` refuses a line whose header was not inserted by the current transaction. **BREAKING** for any tool that adds lines to an existing entry; the application never does, and the append-only design already forbids it in spirit.
- **3. Error details repeat at most 64 characters of a rejected value.** A 200,000-character `owner_ref` gives a 200 KB 400, and a 200,000-character `account_code` a 200 KB 422. The echoes in `Account` (`code`, `owner_ref`), `InvalidAccountCodeException`, `IdempotencyKey.requireWellFormed` and the reversal's `entry_id` are cut at 64 characters with an ellipsis; shorter values stay whole. The detail of a *reused* key keeps naming its validated (at most 255 characters) key in full.
- **4. Only the query string is read for query parameters.** With a query string present, `getParameterMap()` also parses a form-encoded POST body: `POST /api/v1/accounts?x=1` with body `code=a&type=ASSET` answered 400 "unknown query parameter(s): code, type, x", and Tomcat read up to 2 MB of that body, past the body limit. The interceptor and `AsOfParameter` now parse the query string themselves, with the same strict UTF-8 decoding. Side effect: a parameter with no name (`?=1`) gets its own accurate detail instead of the "not valid percent-encoded UTF-8" text that was false for it.
- **5. The transaction bound is described as what it is.** `ALTER ROLE ledger_app SET transaction_timeout` sets a *default*; the role can `SET transaction_timeout = 0` in its own session, and `TransactionBoundGuard` reads the setting once at startup. README, `PROJECT-CONVENTIONS.md`, the init script's comment and the `account-balances` requirement now say that the as-of guarantee assumes a correct application, and a test pins the limit. Docs and a test only.
- **6. `owner_ref` length counts characters, not UTF-16 units.** 51 emoji (102 UTF-16 units) were refused although the spec says "at most 100 characters" and `VARCHAR(100)` stores them. The check counts code points. The same requirement now also records two `owner_ref` rules that were added without a spec change: padding is Unicode whitespace, and a value the database cannot keep as sent (NUL, an unpaired surrogate) is refused.

Behavior-neutral (same responses, tests unchanged or moved):

- **7.** The reversal endpoint reads one byte of its body to learn whether there is one, instead of binding the whole body as `byte[]`. A declared `Content-Length` over the limit is still a 413 on that first read; an oversized *chunked* body is now answered by its first byte, a 400 "must not have a body".
- **9.** Account registration goes through a `RegisterAccount` use case, as the conventions already require for writes.
- **10.** The duplicated 404 goes away: the account lookup throws the domain `AccountNotFoundException` (same message, already mapped to 404), and `ResourceNotFoundException` and its handler are deleted.
- **11.** `RequestBodyTooLargeException` loses its unused `limit` field and accessor.

Not code: **8.** `mvnw` needs its executable bit when the repository is first committed (`core.filemode=false` on this checkout): `git add --chmod=+x mvnw`. Recorded in design.md and at the end of tasks.md.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `account-registration`: `Owner reference validation` (length in characters, Unicode whitespace padding, text the database cannot keep); `The account endpoints accept no query parameters` (body fields are not query parameters; a parameter with no name); new requirements for the body-size limit and for bounded error details.
- `journal-entries`: `Posting accepts no query parameters` (same two scenarios); new requirements for the body-size limit and for bounded error details.
- `journal-entry-reversal`: `The reversal request accepts no query parameters` (same two scenarios); new requirements for the body-size limit and for bounded error details.
- `ledger-append-only`: a new requirement that a line can only be added in the transaction that inserts its entry.
- `account-balances`: `A settled answer cannot change after the fact` states the transaction bound as a default the application keeps.

## Impact

- **Migration:** `V6__entry_lines_only_with_their_entry.sql` (one function, one trigger, `LDG06`). No data change. The owner is bound by it too, like the other append-only triggers; the owner can still disable it, which the README already says about every trigger.
- **Code:** `adapter.web` (`RequestBodyLimitFilter`, `QueryParameterInterceptor`, `AsOfParameter`, a new strict `QueryString` reader, `ApiExceptionHandler`, `JournalEntryController`, `AccountController`, `EchoedName`), `domain` (`Account`, `InvalidAccountCodeException`, `IdempotencyKey`, a small bounded-echo helper), new `application.account.RegisterAccount`. Deleted: `ResourceNotFoundException`.
- **Tests:** new socket tests (path spellings, form-body-with-query-string), a real-commit raw-row test for `LDG06`, unit tests for the query-string reader and the bounded echo, an `owner_ref` code-point test through a real database; a few existing tests that insert lines in a different transaction from their header move into one (found by running the suite on the trigger, as V5 did). `AccountBalanceControllerTest`'s simulated-decoding filter goes away: the application now decodes the query string itself, so MockMvc can exercise it.
- **QA-owned:** nothing in `src/contractTest` is edited. The expected contract result is unchanged (937 invocations, 29 skipped, 0 failures): the largest payload that suite sends is a 100,000-character `owner_ref` (under the body limit, and a 400 by its own rule), and its one assertion on a long echoed value is the 255-character reused key, which stays whole.
- **Docs:** `README.md`, `PROJECT-CONVENTIONS.md`, `docker/initdb/01-create-ledger-app-role.sql` (a comment).

**Out of scope:** the Unicode-aware database backstop for `owner_ref` padding (V1's `= trim()` only catches spaces; domain and database stay inconsistent for exotic whitespace, the application being the only writer); echoing the path-derived `code` in the account 404 (bounded by Tomcat's request-line limit, and the 404 text is the contract); the two deprecation warnings in the QA-owned `ContractTestBase`; authentication, pagination and the other limits listed in the README. If the `owner_ref` backstop is done later it is `V7`.
