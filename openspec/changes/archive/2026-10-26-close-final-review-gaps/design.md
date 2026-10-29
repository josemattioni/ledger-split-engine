## Context

Everything below was read from the tree as it stands (648 unit tests, contract suite 937 invocations / 29 skipped / 0 failures). The facts that shape the decisions:

- `RequestBodyLimitFilter` is a `OncePerRequestFilter` `@Component` at the default (lowest) order, gated by `request.getRequestURI().startsWith("/api/v1/")`, and fails the *read* of a body that goes past 256 KiB (`RequestBodyTooLargeException`, found in the cause chain by `ApiExceptionHandler`, answered 413).
- `QueryParameterInterceptor` (every `/api/v1/**` handler) and `AsOfParameter` both call `request.getParameterMap()`, and map Tomcat's `InvalidParameterException` to the fixed "not valid percent-encoded UTF-8" detail.
- The adapter inserts an entry as one `@Transactional` unit: the header (`INSERT … ON CONFLICT … DO NOTHING RETURNING`), then the lines in one `batchUpdate`. `entry_lines` has an `AFTER` constraint trigger for the balance (`LDG01`), the append-only triggers (`LDG02`, `BEFORE UPDATE OR DELETE` and `BEFORE TRUNCATE`), and no `BEFORE INSERT` trigger. The header triggers `LDG03`/`LDG04`/`LDG05` fire on header inserts only.
- `PostJournalEntry`, `ReverseJournalEntry` and `ReadAccountBalance` are the use cases; account registration calls `AccountPort` straight from the controller.
- The contract suite (QA-owned) sends owner references of up to 100,000 characters and asserts a long value echoed in full in exactly one place: the detail of a *reused* 255-character idempotency key.

See proposal.md for why; this document is how.

## Goals / Non-Goals

**Goals:**
- Close each of items 1–6 with a test that fails before the change and passes after it (mutation-checked, as in the earlier slices), and keep 7, 9, 10 and 11 behavior-neutral.
- No edit under `src/contractTest`; the expected contract result is unchanged.
- State the transaction bound's real strength where it is claimed.

**Non-Goals:**
- A Unicode-aware database backstop for `owner_ref` padding (V1's `= trim()` only catches spaces). Deliberately deferred: the application is the only writer, and the domain rule is the primary control. If it is done later it is `V7`.
- Changing the 404 text for an unknown account code (it echoes a path value bounded by Tomcat's request-line limit, and its text is the contract).
- Authentication, pagination, `GET` of an entry, account status (listed in the README as not built).

## Decisions

### 1. The body limit counts every request; it does not decide by path (item 1)
Any rule of the form "only paths that start with `/api/v1/`" has to agree with how Spring matches handlers, which is on the decoded path with `;` parameters removed and `//` collapsed. The reviewed gate compared the raw URI, so `/api;x=1/v1/accounts`, `/api/%761/accounts` and `/%61pi/v1/accounts` reached handlers unlimited, and `//api/v1/accounts` is another spelling of the same class. The fix is to remove the gate: `shouldNotFilter` goes, and the filter wraps every request. A request whose handler never reads a body is unaffected (the wrapper counts reads, it does not read), the `/error` dispatch is skipped by `OncePerRequestFilter` as before, and there is no longer a path to spell differently.

*Alternatives considered.* `request.getServletPath()`: empty under MockMvc (the path is in `pathInfo` there), and it is Tomcat's view of the path, not Spring's. `FilterRegistrationBean.addUrlPatterns("/api/v1/*")`: container matching on the decoded path is correct, but a `@Configuration`-declared registration is not loaded by `@WebMvcTest` slices (the same reason `WebConfiguration` is a `@Component`), and it is still a gate that has to stay in sync. `UrlPathHelper.getPathWithinApplication`: mirrors Spring today, still a gate. Removing the gate is both the simplest and the only one that cannot be out of sync.

**A second route to the same hole, found while grounding (to be confirmed by a failing test first).** Spring Boot registers `OrderedFormContentFilter` at order -9900, which for `PUT`, `PATCH` and `DELETE` requests with a form-encoded body reads and parses the whole body into request parameters, before a filter at the default order wraps the stream. Those methods are `405` on every endpoint, but the body is read before the 405. The filter therefore moves ahead of it (`@Order(Ordered.HIGHEST_PRECEDENCE)`), and because the limit's exception then surfaces from inside that filter, outside the `DispatcherServlet` and so outside `ApiExceptionHandler`, the limit filter itself catches a `RequestBodyTooLargeException` that escapes the chain and answers 413 problem+json with a fixed body (the limit only, nothing of the request, no `instance`). The task writes the `DELETE` socket test first and records that it fails (405) before the fix.

**Tests.** Over a real socket (MockMvc does no Tomcat path decoding): the three reviewed spellings, `;` on the reversal path, `//`, each with a body over the limit declared and chunked, must get 413; plus the `DELETE` with a form body. `RequestBodyLimitFilterTest` replaces "a request outside the API is passed on untouched" with "every request is wrapped".

### 2. Query parameters come from the query string alone, read by the application (item 4)
`getParameterMap()` on a POST with a form content type parses the *body* as parameters, which is how `POST /api/v1/accounts?x=1` with `code=a&type=ASSET` came to be answered "unknown query parameter(s): code, type, x", and why Tomcat read up to 2 MB of it, past the body limit. A new `QueryString` reader in `adapter.web` parses `request.getQueryString()` and nothing else:

- Segments split on `&`; empty segments are ignored (`?&&`, `?x=1&`), as Tomcat does. A segment is `name=value` split at the first `=`; with no `=` it is a name with an empty value.
- Names and values are percent-decoded, `+` is a space (as in a query string today), into bytes and then decoded as **strict UTF-8** (`CharsetDecoder` with `REPORT`, not `URLDecoder`, which silently replaces an invalid sequence). A `%` not followed by two hex digits, a truncated `%`, or an invalid UTF-8 sequence (`%E9`, `%C0%AF`) gives the fixed detail "the query string is not valid percent-encoded UTF-8", unchanged.
- A segment with an empty name and a `=` (`=1`, `=`) gives its own fixed detail, "the query string has a parameter with no name". Tomcat rejected this case with the same exception as a bad encoding, so the "not valid percent-encoded UTF-8" text was false for it; the README and conventions recorded that as a known imprecision, and it goes away. *Assumption, recorded rather than asked:* if the old text for this one case is preferred, it is one constant.
- The result is a `Map<String, List<String>>` in encounter order. `QueryParameterInterceptor` compares its names with `@AcceptedQueryParameters`; `AsOfParameter` reads `as_of` values from the same reader instead of `getParameterMap()` (a repeated `as_of` is still two values; an empty one still `""`). Nothing in `src/main` calls `getParameterMap()` afterwards, which a test greps for.
- No request-line parameter-count limit is added: Tomcat's 8 KB request-line limit already bounds the query string.

Because the application now decodes the query string, `MockMvc` exercises the real decoding. `AccountBalanceControllerTest`'s simulated-decoding filter (a wrapper that made `getParameterMap()` throw) is deleted, and the "MockMvc never runs Tomcat's decoder" notes in the conventions go. A socket test stays for the form-body case and for what only Tomcat produces (the request line itself).

### 3. One bounded echo, in the domain (item 3)
A new `domain.EchoedValue` (`excerpt(String)`, `quoted(String)`) cuts a value at 64 **code points** (not UTF-16 units, so a surrogate pair is never split) and appends an ellipsis; 64 or fewer stay whole. `adapter.web.EchoedName` is deleted and its two users (the query-parameter interceptor and the unrecognized-field detail) call `EchoedValue.quoted` directly, so there is one constant and no wrapper class (apply: simpler than the delegate first planned). Applied where the value is unvalidated and unbounded:

- `Account`: the `code` and `owner_ref` messages.
- `InvalidAccountCodeException`: the line's `account_code`.
- `IdempotencyKey.requireWellFormed`: the malformed key.
- `JournalEntryController.canonicalUuid`: the malformed `entry_id`.

Left whole on purpose: the detail of a key *reused* for a different request (a well-formed key, at most 255 characters, and QA's `detailNamesLongKey` asserts it whole), and the account 404 (path-derived, bounded by the request-line limit, and the text is the contract).

### 4. `owner_ref` length in characters (item 6)
`ownerRef.codePointCount(0, ownerRef.length()) > MAX_OWNER_REF_LENGTH`. The database already agrees: PostgreSQL's `VARCHAR(100)` and `length()` count characters, not UTF-16 units. A test through the real database stores and reads back 100 emoji (200 UTF-16 units) and shows 101 refused by the domain. The requirement is also where two earlier, unspecified `owner_ref` rules are recorded (Unicode whitespace padding; text the database cannot keep as sent), since it is being modified anyway.

### 5. `LDG06`: a line can only be added in the transaction that inserted its entry (item 2)
`V6__entry_lines_only_with_their_entry.sql` adds one function and one `BEFORE INSERT … FOR EACH ROW` trigger on `entry_lines`:

```sql
SELECT xmin INTO header_xmin FROM journal_entries WHERE id = NEW.journal_entry_id;
IF FOUND AND header_xmin <> pg_current_xact_id()::xid THEN
    RAISE EXCEPTION '…' USING ERRCODE = 'LDG06';
END IF;
RETURN NEW;
```

`xmin` of a row is the transaction that inserted it, and `journal_entries` is never updated (`LDG02`), so it is a reliable "inserted by me" test. If the header does not exist (`NOT FOUND`) the trigger returns the row and the foreign key gives the `23503` it always gave. `SELECT` on `journal_entries` is all `ledger_app` needs. It binds the owner too, like the other append-only triggers; the owner can still disable it, which the README already says of every trigger. It fires `BEFORE INSERT`, so the reproduced attack (`SET CONSTRAINTS ALL IMMEDIATE`) is refused at the insert, not at a commit it might never reach.

*What holds.* A committed old header keeps its raw `xmin` whether or not it is frozen (since PostgreSQL 9.4 `VACUUM (FREEZE)` sets the frozen bits and leaves `xmin` alone; observed on 17: `xmin` unchanged, `t_infomask & 768 = 768`), and the trigger compares the low 32 bits of the current xid with it, so in practice it never matches. They can coincide only after the xid counter wraps (2^32 transactions): a transaction whose xid then equals an old header's `xmin` in those 32 bits would pass the check. That window is theoretical; a 64-bit `xid8` column (alternative (a) below) would close it. The check costs one primary-key lookup per line (at most 100 per entry).

> **Correction (2026-10-27, from a code review, reproduced).** This paragraph was headed "Verified properties" and said a frozen header has `xmin` = 2, and that the 32-bit truncation cannot collide because freezing keeps live tuples within about 2^31 transactions of the current one. Neither was verified, and both are wrong: a frozen tuple keeps its raw `xmin`. The "Restores" bullet below made the same kind of claim (that `pg_restore --single-transaction` works) and is corrected too.

*Known limits, documented and pinned by tests.*
- **Savepoints.** A header inserted in a subtransaction has the subtransaction's xid as its `xmin`, which is not `pg_current_xact_id()` (always the top-level xid). Lines of such a header are refused, **even inside the same savepoint**, not only after `RELEASE SAVEPOINT` (apply: the first test wrote the narrower claim and failed; the same-savepoint case is now pinned too). The application uses no savepoints (no `NESTED` propagation, no pgjdbc `autosave`, no PL/pgSQL `EXCEPTION` block around an entry insert), so this is a documented limit with tests that pin it, not a case to handle (SQL has no way to ask "is this xid one of my subtransactions").
- **Restores.** Loading a dump inserts headers and lines in separate statements. A data-only `pg_restore` with the triggers on fails, even with `--single-transaction`: `pg_restore` runs with an empty `search_path` and the trigger functions name their tables without a schema. A full dump restores (the triggers are created after the data) and so does a data-only load with `--disable-triggers`; both were run on 2026-10-27. (First written as "works inside one transaction (`pg_restore --single-transaction`)", which was not tried and is false as the functions stand.) `docs/ledger-guarantees.md` states the working recipes.

*Alternatives considered.* (a) A `tx_id xid8 DEFAULT pg_current_xact_id()` column on `journal_entries`, compared with `pg_current_xact_id()`: immune to savepoints, but it adds a column to the ledger table and a restore would then reload the *old* `tx_id` values, so it fails even inside one transaction; the savepoint limit costs less. (b) Revoking `INSERT` on `entry_lines` and posting through a `SECURITY DEFINER` function: closes the hole by construction but is a redesign of every insert and test. (c) An `AFTER` constraint trigger comparing the line count at commit: it would see the *new* lines as part of the entry and could not tell an added line from an original one.

*Tests that must change.* Tests that insert an entry's lines in a different transaction from its header would fail with `LDG06`; they were to be found by running the suite on the trigger (as V5 did) and moved into one transaction. Apply result: **none needed moving**. The whole `src/test` suite (768 tests) passed on the trigger as it stood, because every existing test already inserts a header and its lines in one transaction.

*Migration numbers.* This is `V6`. The `owner_ref` Unicode backstop, if done, is `V7`.

### 6. The transaction bound is a default the application keeps (item 5)
`ALTER ROLE … SET transaction_timeout` is a per-role default applied at session start. The role owns its session settings, so it can `SET transaction_timeout = 0`, and `TransactionBoundGuard` checks once, at startup. Every place that says "every transaction of `ledger_app` ends after 30 seconds" is reworded to say the bound is a default the application keeps and the as-of guarantee assumes the application does not change it (README: the as-of paragraph and the guarantees section, plus the Postgres 17 note; `PROJECT-CONVENTIONS.md`: the as-of bullet; the init script's comment; the `account-balances` requirement, with a scenario). `TransactionTimeoutTest` gains a test that a `ledger_app` session can switch its own bound off, so the stated limit is demonstrated rather than asserted.

### 7. The behavior-neutral items
- **Reversal body check (7).** `JournalEntryController.reverse` drops the `@RequestBody(required = false) byte[]` and reads one byte from the request's input stream; any byte means there is a body, with the same 400 detail. A declared `Content-Length` over the limit still fails on that first read (413). That `RequestBodyTooLargeException` is thrown from the controller itself, so it is not wrapped by a message converter as the Jackson reads are; `ApiExceptionHandler` gets a direct `@ExceptionHandler` for it (and the cause-chain check stays for the converter path), and any other `IOException` on that one read is a 400 "the request body could not be read" (it used to surface as a `HttpMessageNotReadableException`, also a 400). The only observable difference: an oversized *chunked* body is answered by its first byte (400) instead of read to the limit (413). No test expects the old answer (`RequestBodyLimitApiIntegrationTest` uses a declared length), and the conventions' "a request whose handler never reads a body is unaffected" now covers this endpoint more fully.
- **`RegisterAccount` (9).** `application.account.RegisterAccount`, not `@Transactional` (the adapter owns the transaction), a thin call to `AccountPort.register` on purpose: it is where the next rule (an account status, an audit entry) goes. The controller builds the `Account` inside `InvalidRequestException.guard` as today and calls the use case. The lookup stays a direct port call (a plain read). `AccountControllerTest` imports the real use case over the mocked port, so its tests are unchanged; a small `RegisterAccountTest` pins delegation and exception propagation.
- **Duplicated 404 (10).** `AccountController.findByCode` throws `AccountNotFoundException(code)`, whose message is identical (`No account with code "<code>"`) and already maps to 404. `ResourceNotFoundException` and `handleResourceNotFound` are deleted; a grep shows no other use.
- **Dead code (11).** The `limit` field and `limit()` of `RequestBodyTooLargeException` go; its message already carries the figure.

### 8. At the first commit (item 8, not code)
`core.filemode=false` on this checkout, so Git does not record the executable bit. Commit the wrapper with `git add --chmod=+x mvnw` (or `git update-index --chmod=+x mvnw` after adding it), otherwise `./mvnw` fails with "Permission denied" on Linux, macOS and CI. Nothing in this change edits `mvnw`.

## Risks / Trade-offs

- **[`LDG06` refuses something legitimate]** → The application inserts headers and lines together and the owner's seeding helper does too; the suite is run on the trigger and every refusal is a test that was inserting across transactions. A future bulk tool or restore must use one transaction or disable the trigger (README).
- **[The savepoint limit]** → Documented, pinned by a test, and a one-line comment in the migration; revisited only if the application ever uses a savepoint.
- **[Own query-string parser drifts from Tomcat's]** → Its behavior is specified by the cases above, and the QA suite exercises about seventy query-string cases (encodings, empty segments, repeats, near-miss names) against the running app; the new reader has its own table of cases, including each invalid-UTF-8 shape.
- **[Wrapping every request]** → One small wrapper object per request and a counter; nothing reads the body unless a handler does.
- **[The nameless-parameter detail changes text]** → QA accepts 200 or 400 for `?=1` and does not assert the old text; the specs gain a scenario for it.
- **[An oversized chunked reversal body is 400, not 413]** → Both are client errors, the request is rejected before anything is written, and the first byte is all the rule needs.
- **[A cut value hides the tail of what the client sent]** → The detail identifies the problem and quotes the beginning; the full value was the client's own.

## Migration Plan

1. Re-measure the baseline (task 1.1).
2. Apply the code changes group by group, each with its tests, mutation-checked.
3. `V6` applies as the owner on the next start; no data change, no volume recreation (a trigger only). The init script change is a comment.
4. Rollback is reverting the code. `V6` stays (an applied migration is never edited) and is harmless to the old code, which inserts headers and lines in one transaction; removing it would be a new migration.

## Open Questions

None. The choices that could have gone another way (removing the path gate instead of fixing it, the `xmin` mechanism over a column, the accurate nameless-parameter detail, deferring the `owner_ref` database backstop) are decisions above, with their alternatives.
