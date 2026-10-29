# journal-entries Specification

## Purpose
Lets a client move money through the ledger by posting a balanced, multi-line journal entry against existing accounts, with the validation rules that keep every entry consistent with double-entry bookkeeping and with the engine's single-currency constraint.

## Requirements

### Requirement: Post a balanced journal entry
The system SHALL allow a client to post a journal entry consisting of a list of lines, each naming an existing account by `account_code` and a signed `amount_cents`, where the lines are inserted together as a single unit. A posted entry is always a real movement of money: it has at least two lines, no line has a zero amount, and no account appears on more than one line (see the requirements below). Every representation of an entry that the system returns includes a `reverses_entry_id`, which is `null` for an entry posted this way: it holds a value only for a reversal (see the `journal-entry-reversal` capability).

#### Scenario: Successful post
- **WHEN** a client submits a journal entry of two or more lines on distinct existing accounts, each with a non-zero `amount_cents`, whose `amount_cents` values sum to zero
- **THEN** the system creates the entry and responds 201 Created with the entry's `entry_id`, `idempotency_key`, `created_at`, a `reverses_entry_id` of `null`, and its lines in submitted order, each with `line_number`, `account_code`, and `amount_cents`

### Requirement: Journal entry lines are numbered by submitted order
The system SHALL assign each line a `line_number` equal to its 1-based position in the submitted `lines` array, and SHALL return the lines in that same order.

#### Scenario: Line numbers reflect submission order
- **WHEN** a client submits a journal entry with three lines
- **THEN** the response lists the lines in the order submitted, numbered 1, 2, and 3

### Requirement: A journal entry must balance
The system SHALL reject a journal entry whose lines do not sum to exactly zero, including a case where the total cannot be represented because it would overflow the supported amount range.

#### Scenario: Unbalanced entry rejected
- **WHEN** a client submits a journal entry whose `amount_cents` values do not sum to zero
- **THEN** the system responds 422 Unprocessable Entity with a detail describing that the entry does not balance, and creates nothing

#### Scenario: Overflowing sum rejected
- **WHEN** a client submits a journal entry whose `amount_cents` values cannot be summed without overflowing the supported integer range
- **THEN** the system responds 422 Unprocessable Entity and creates nothing, the same as an unbalanced entry

### Requirement: Line amounts are limited in magnitude
The system SHALL reject a journal entry containing a line whose `amount_cents` has an absolute value greater than 1,000,000,000,000 (10^12 centavos, i.e. 10 billion BRL), so that an account's balance can never grow beyond what the system is able to represent and return.

#### Scenario: Over-limit amount rejected even when the entry balances
- **WHEN** a client submits a journal entry containing a line whose `amount_cents` is 9223372036854775807 and another line of the same magnitude with the opposite sign, so that the entry would otherwise sum to zero
- **THEN** the system responds 422 Unprocessable Entity with a detail stating the permitted range, and creates nothing

#### Scenario: Amount at the limit accepted
- **WHEN** a client submits a balanced journal entry containing lines of exactly +1,000,000,000,000 and -1,000,000,000,000
- **THEN** the system accepts the entry

### Requirement: A journal entry must have at least two lines
The system SHALL reject a journal entry that has fewer than two lines, with 422 Unprocessable Entity, whether or not its amounts would otherwise sum to zero.

#### Scenario: Entry with no lines rejected
- **WHEN** a client submits a journal entry whose `lines` array is empty
- **THEN** the system responds 422 Unprocessable Entity with a detail stating that an entry needs at least two lines, and creates nothing

#### Scenario: Entry with one line rejected
- **WHEN** a client submits a journal entry with exactly one line, whatever its amount
- **THEN** the system responds 422 Unprocessable Entity reporting the two-line minimum (not an imbalance), and creates nothing

#### Scenario: Entry with two lines accepted
- **WHEN** a client submits a balanced journal entry with exactly two non-zero lines on distinct existing accounts
- **THEN** the system accepts the entry

### Requirement: Line amounts must not be zero
The system SHALL reject a journal entry containing a line whose `amount_cents` is zero, with 422 Unprocessable Entity, even when the entry would otherwise balance.

#### Scenario: Zero-amount line among otherwise valid lines rejected
- **WHEN** a client submits a journal entry with lines of 250, 0 and -250 on three distinct existing accounts
- **THEN** the system responds 422 Unprocessable Entity with a detail identifying the zero-amount line, and creates nothing

### Requirement: An account may appear at most once in an entry
The system SHALL reject a journal entry in which the same `account_code` appears on more than one line, with 422 Unprocessable Entity, even when the entry would otherwise balance and even when the repeated lines cancel each other out.

#### Scenario: Repeated account across several lines rejected
- **WHEN** a client submits a journal entry with lines of 100 and 50 on the same account and -150 on another account
- **THEN** the system responds 422 Unprocessable Entity with a detail identifying the repeated `account_code`, and creates nothing

#### Scenario: Debit and credit on the same account rejected
- **WHEN** a client submits a journal entry with lines of 700 and -700 on the same account
- **THEN** the system responds 422 Unprocessable Entity and creates nothing, rather than posting a net-zero entry

### Requirement: Every line must reference an existing account
The system SHALL reject a journal entry containing a line whose `account_code` is well-formed (see "Every line's account code must be well-formed") but does not match any registered account.

#### Scenario: Unknown account referenced
- **WHEN** a client submits a journal entry with a line whose well-formed `account_code` does not match any registered account
- **THEN** the system responds 422 Unprocessable Entity with a detail identifying the unrecognized `account_code`, and creates nothing

### Requirement: All lines in an entry must share one currency
The system SHALL reject a journal entry whose lines reference accounts that do not all share the same currency.

#### Scenario: Mixed-currency entry rejected
- **WHEN** a client submits a journal entry whose lines reference accounts of more than one currency
- **THEN** the system responds 422 Unprocessable Entity with a detail describing the currency mismatch, and creates nothing

### Requirement: Posting requires an idempotency key
The system SHALL require an `Idempotency-Key` request header on every post, consisting of 1 to 255 visible (non-whitespace, printable) ASCII characters, and SHALL reject a post that lacks one or whose value does not meet that shape.

#### Scenario: Missing idempotency key rejected
- **WHEN** a client posts a journal entry without an `Idempotency-Key` header
- **THEN** the system responds 400 Bad Request and creates nothing

#### Scenario: Malformed idempotency key rejected
- **WHEN** a client posts a journal entry with an `Idempotency-Key` header that is empty, exceeds 255 characters, or contains a character other than a visible ASCII character
- **THEN** the system responds 400 Bad Request and creates nothing

#### Scenario: Repeated idempotency key header rejected
- **WHEN** a client posts a journal entry with more than one `Idempotency-Key` header
- **THEN** the system responds 400 Bad Request and creates nothing — the headers are never merged into a single combined key, since a later retry with just one of them would then not be recognized as a duplicate

### Requirement: Idempotency keys are unique
The system SHALL ensure that an `Idempotency-Key` identifies at most one journal entry, for as long as that entry exists — keys never expire. A post whose key matches an existing entry and whose request is identical to the one that created that entry is a retry: the lines are identical and, for a reversal, so is the entry being reversed. The system SHALL respond to a retry 201 Created with the original entry exactly as first returned (the same `entry_id`, `idempotency_key`, `created_at`, `reverses_entry_id`, and lines in `line_number` order) and the response header `Idempotent-Replayed: true`, and SHALL write nothing. The header SHALL be present on a replay and only on a replay. A post whose key matches an existing entry but whose request differs is a reuse of the key and is rejected (see "A key reused for a different request is rejected"). A repeated key is never answered with 409 Conflict.

#### Scenario: Identical retry replays the original response
- **WHEN** a client posts a journal entry successfully and then posts the identical lines again with the same `Idempotency-Key`
- **THEN** both responses are 201 Created with identical bodies, including the same `entry_id` and `created_at`, and only the second carries `Idempotent-Replayed: true`

#### Scenario: A replay writes nothing
- **WHEN** a client retries a successful post with the same key and the same lines
- **THEN** the balances of the accounts involved count the entry once, and no new entry exists

#### Scenario: A first post carries no replay header
- **WHEN** a client posts a journal entry with a key that has never been used
- **THEN** the response is 201 Created and has no `Idempotent-Replayed` header

#### Scenario: A retry long after the original is still replayed
- **WHEN** a client retries a post whose original entry is arbitrarily old, with the same key and the same lines
- **THEN** the system replays the original entry rather than treating the key as new

#### Scenario: Repeated idempotency key rejected
- **WHEN** a client posts using an `Idempotency-Key` that was already used by a prior successful post, but with lines that differ from that post's
- **THEN** the system responds 422 Unprocessable Entity (not 409 Conflict) and creates no new entry, leaving the original entry unchanged

### Requirement: Malformed post requests are rejected
The system SHALL reject, with 400 Bad Request, a post whose body is not well-formed: missing or unrecognized fields, a property that appears more than once in the same JSON object, a line with the wrong JSON type for `account_code` (anything other than a string) or `amount_cents` (anything other than an integer number), or an `amount_cents` value outside the supported integer range. The system SHALL NOT convert a value from one JSON type to another to make it fit, and SHALL NOT pick one of several values given for the same property.

#### Scenario: Malformed line rejected
- **WHEN** a client submits a journal entry with a line missing `account_code` or `amount_cents`, or with a non-numeric `amount_cents`
- **THEN** the system responds 400 Bad Request and creates nothing

#### Scenario: Wrongly-typed line values are not converted
- **WHEN** a client submits a line whose `amount_cents` is a JSON string (for example `"-100"`), or whose `account_code` is a JSON number or boolean
- **THEN** the system responds 400 Bad Request and creates nothing, rather than converting the value and proceeding

#### Scenario: A property repeated within a line is rejected
- **WHEN** a client submits an otherwise valid entry in which one line gives `amount_cents` twice (for example `"amount_cents":0,"amount_cents":-250`)
- **THEN** the system responds 400 Bad Request with the default problem type and creates nothing, rather than keeping either value

#### Scenario: A repeated lines property is rejected
- **WHEN** a client submits a body that gives `lines` twice
- **THEN** the system responds 400 Bad Request with the default problem type and creates nothing

### Requirement: Journal entry identity is server-assigned
The system SHALL assign the `entry_id` returned for a posted journal entry itself; a client-supplied `entry_id` (or any internal database id) SHALL NOT appear in, or be accepted from, the request or response. The only identifiers an entry representation carries are the entry's own `entry_id` and `reverses_entry_id`, which is `null` for an ordinary entry and for a reversal is the public `entry_id` of the entry it reverses.

#### Scenario: Response carries only server-assigned identity
- **WHEN** a journal entry is successfully posted
- **THEN** the response's `entry_id` is assigned by the system, no internal database id appears anywhere in the response, and the only other identifier, `reverses_entry_id`, is `null`

#### Scenario: A reversal's link is a public identifier
- **WHEN** a reversal is returned
- **THEN** its `reverses_entry_id` equals the `entry_id` of the entry it reverses, and no internal database id appears anywhere in the response

### Requirement: Journal entry errors are reported as problem+json
The system SHALL report every error response for posting a journal entry as `application/problem+json`, with a detail describing the violated rule, including the case where the system detects that the balance rule was violated only after accepting a request it should have rejected up front. A 400 SHALL be used only for a request the client got wrong; a failure inside the system SHALL be reported as an internal error, never as a client error. The one exception is a request that the HTTP server itself rejects before the application receives it, such as one whose `Idempotency-Key` header contains a control character like DEL (0x7F) or 0x01: the server answers 400 Bad Request with its own error page, which is not `application/problem+json`, and nothing is created. A character the application does receive (a space, a tab or a non-ASCII byte in that header) is answered with `application/problem+json` as above.

#### Scenario: Validation and conflict errors carry a description
- **WHEN** a post is rejected as malformed (400) or as violating a ledger rule (422), including reuse of an idempotency key for a different request
- **THEN** the response has `Content-Type: application/problem+json` and a human-readable `detail` describing what was wrong

#### Scenario: Internal balance-check failure still yields a problem response
- **WHEN** a posted entry passes the system's own pre-insert balance check but is later found unbalanced by the storage layer's own backstop check
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail that does not leak internal detail, and logs the failure for investigation

#### Scenario: An internal failure is never reported as a client error
- **WHEN** a valid post fails because a component inside the system rejects an argument it was given
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and the generic detail, not 400 with the component's own message, and logs the failure for investigation

#### Scenario: A request the HTTP server rejects before the application sees it is still a 400
- **WHEN** a client posts a journal entry with an `Idempotency-Key` header containing a control character such as DEL (0x7F) or 0x01
- **THEN** the HTTP server responds 400 Bad Request with its own error page rather than `application/problem+json`, and no entry is created

### Requirement: Ledger-rule violations are distinguishable by problem type
The system SHALL report each 422 violation — of a ledger rule, of the reversal rules, or of the idempotency-key rule — with its own stable problem `type`, so that the violated rule can be identified from the `type` and not only from the status code or the wording of `detail`. The types are:

| Rule | Problem `type` |
|---|---|
| Entry does not balance (including an overflowing sum) | `urn:ledger-split-engine:problem:unbalanced-entry` |
| Degenerate entry (fewer than two lines, a zero-amount line, or a repeated account) | `urn:ledger-split-engine:problem:degenerate-entry` |
| Entry has more lines than permitted | `urn:ledger-split-engine:problem:line-limit-exceeded` |
| Line's account code is not well-formed | `urn:ledger-split-engine:problem:invalid-account-code` |
| Line references an unknown account | `urn:ledger-split-engine:problem:unknown-account` |
| Lines span more than one currency | `urn:ledger-split-engine:problem:mixed-currency` |
| Line amount outside the permitted range | `urn:ledger-split-engine:problem:amount-limit-exceeded` |
| Idempotency key already used for a different request | `urn:ledger-split-engine:problem:idempotency-key-reused` |
| The entry to reverse is itself a reversal | `urn:ledger-split-engine:problem:cannot-reverse-a-reversal` |
| The entry to reverse has already been reversed | `urn:ledger-split-engine:problem:entry-already-reversed` |

The three degenerate-entry cases share one type; the `detail` states which of them applies. No two rules share a type.

#### Scenario: Unbalanced entry reports the unbalanced-entry type
- **WHEN** a client submits two valid lines of +100 and -99 on distinct existing accounts
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:unbalanced-entry`

#### Scenario: Degenerate entry reports the degenerate-entry type and says which case
- **WHEN** a client submits an otherwise valid entry that is degenerate in exactly one way — an empty `lines` array, a zero-amount line, or a repeated account
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:degenerate-entry` and a `detail` that names that specific case

#### Scenario: Too many lines reports the line-limit-exceeded type
- **WHEN** a client submits an otherwise valid balanced entry with more lines than permitted
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:line-limit-exceeded`

#### Scenario: A malformed account code reports the invalid-account-code type
- **WHEN** a client submits an otherwise valid balanced entry in which one line's `account_code` is not well-formed
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:invalid-account-code`

#### Scenario: Unknown account reports the unknown-account type
- **WHEN** a client submits an otherwise valid balanced entry in which one line names, with a well-formed code, an account that does not exist
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:unknown-account`

#### Scenario: Over-limit amount reports the amount-limit-exceeded type
- **WHEN** a client submits an otherwise valid balanced entry containing a line whose `amount_cents` exceeds the permitted range
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:amount-limit-exceeded`

#### Scenario: Mixed currencies report the mixed-currency type
- **WHEN** a client submits an otherwise valid balanced entry whose lines reference accounts of more than one currency
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:mixed-currency`

#### Scenario: Reused idempotency key reports the idempotency-key-reused type
- **WHEN** a client posts a valid entry with a key already used by an existing entry whose lines differ
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:idempotency-key-reused`

#### Scenario: Reversing a reversal reports the cannot-reverse-a-reversal type
- **WHEN** a client requests the reversal of an entry that is itself a reversal
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:cannot-reverse-a-reversal`

#### Scenario: Reversing an entry twice reports the entry-already-reversed type
- **WHEN** a client requests the reversal of an entry that already has a reversal, using a key that has not been used
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:entry-already-reversed`

### Requirement: Storage-layer backstop failures are internal errors
The system SHALL treat a violation of any of the storage layer's own entry-shape safeguards (an entry with fewer than two lines, a zero-amount line, an account repeated within an entry), reversal safeguards (a reversal whose lines are not the exact negation of the entry it reverses, or a reversal of a reversal, which includes a reversal that would reverse itself) or append-only safeguard as an internal error — 500 Internal Server Error with `application/problem+json`, a generic detail that does not leak internal detail, and an error-level log entry — and SHALL NOT report any of them as a conflict or a client error. Posting a journal entry is never answered with 409 Conflict. A second reversal of the same entry, which the storage layer also refuses, is not a backstop failure: it is the expected outcome of a concurrent request and is reported as `entry-already-reversed`.

#### Scenario: Entry-shape backstop failure yields a generic 500
- **WHEN** a posted entry passes the system's own pre-insert checks but the storage layer's own safeguard then rejects it
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail, and logs the failure for investigation

#### Scenario: A repeated account is never reported as a repeated idempotency key
- **WHEN** the storage layer's own safeguard rejects a line because its account already appears in the same entry
- **THEN** the system does not respond 409 Conflict; it responds 500, the same as any other backstop failure, because posting never produces a 409

#### Scenario: Reversal backstop failure yields a generic 500
- **WHEN** a reversal passes the system's own checks but the storage layer's own reversal safeguard then rejects it, because its lines are not the exact negation of the original's
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail, and logs the failure for investigation

### Requirement: A key reused for a different request is rejected
The system SHALL reject, with 422 Unprocessable Entity and problem `type` `urn:ledger-split-engine:problem:idempotency-key-reused`, a post whose `Idempotency-Key` matches an existing journal entry but whose request is not identical to the one that created that entry. The request differs if the lines are not identical, or if one request is a reversal and the other is not, or if they reverse different entries. Lines are identical only if they have the same count and the same `account_code` and `amount_cents` at every `line_number`; the comparison is order-sensitive, so the same lines in a different order are a different request. The `detail` SHALL name the key and SHALL NOT reveal the stored entry's lines or amounts. The system SHALL write nothing, and the original entry SHALL be unchanged.

#### Scenario: Same key with a different amount rejected
- **WHEN** a client posts an entry successfully and then posts the same two accounts with a different amount (still balanced) using the same key
- **THEN** the system responds 422 with the `idempotency-key-reused` type, the original entry and the balances are unchanged, and nothing is created

#### Scenario: Same key with the lines reordered rejected
- **WHEN** a client posts an entry successfully and then posts the same lines in a different order using the same key
- **THEN** the system responds 422 with the `idempotency-key-reused` type, and nothing is created

#### Scenario: Same key with an extra line rejected
- **WHEN** a client posts a two-line entry successfully and then posts a three-line entry using the same key
- **THEN** the system responds 422 with the `idempotency-key-reused` type, and nothing is created

#### Scenario: The rejection names the key but not the stored payload
- **WHEN** a client's post is rejected because its key was used for a different request
- **THEN** the `detail` contains the key and contains neither the stored entry's account codes nor its amounts

#### Scenario: Identical lines are still a different request if one is a reversal
- **WHEN** a client posts an ordinary entry whose lines are exactly the negation of another entry's lines using key K, and then requests the reversal of that other entry using key K
- **THEN** the system responds 422 with the `idempotency-key-reused` type, and nothing is created

### Requirement: Only successful posts consume an idempotency key
The system SHALL treat an `Idempotency-Key` as used only by a post that succeeded. A post rejected for any reason, or that fails with a server error, SHALL leave its key unused, so that the same key can be posted again.

#### Scenario: A key from a rejected ledger-rule violation is reusable
- **WHEN** a client's post is rejected with 422 because a line names an unknown account, and the client then posts a valid entry with the same key
- **THEN** the second post responds 201 Created, with no `Idempotent-Replayed` header, and the entry exists once

#### Scenario: A key from a malformed request is reusable
- **WHEN** a client's post is rejected with 400 because the body is malformed, and the client then posts a valid entry with the same key
- **THEN** the second post responds 201 Created, with no `Idempotent-Replayed` header

### Requirement: Concurrent posts with the same key produce one entry
The system SHALL, when several posts with the same `Idempotency-Key` are in flight at the same time, create exactly one journal entry. Each other post SHALL wait for the first to finish and then be answered exactly as if it had arrived afterwards: replayed if its lines are identical, rejected as a reuse if they differ. If the first post fails and so leaves the key unused, a waiting post SHALL proceed as a new post. No intermediate "in progress" response SHALL be visible to clients.

#### Scenario: Simultaneous identical posts create one entry
- **WHEN** two clients post identical lines with the same key at the same time
- **THEN** both receive 201 Created with identical bodies, exactly one of them carries `Idempotent-Replayed: true`, and exactly one entry exists

#### Scenario: Simultaneous posts with different lines create one entry
- **WHEN** two clients post different lines with the same key at the same time
- **THEN** exactly one receives 201 Created and the other receives 422 with the `idempotency-key-reused` type, and exactly one entry exists

#### Scenario: A failed first post does not block a waiting post
- **WHEN** a post with a key fails and rolls back while a second post with the same key is waiting
- **THEN** the second post proceeds as a new post and, if valid, responds 201 Created

### Requirement: The idempotency check follows request validation and precedes account resolution
The system SHALL evaluate a post in this order: first, whether the request is well-formed (400) and whether the entry satisfies the rules that need no stored data — shape, degenerate entry, line limit, account-code format, amount limit, balance (422) — regardless of its key; second, the idempotency key, answering a retry from what is stored; third, and only for a key not yet used, whether the accounts exist and share one currency (422). A retry SHALL therefore be answered from the stored entry without depending on anything else, and a reused key that also names an unknown (but well-formed) account SHALL report `idempotency-key-reused`.

#### Scenario: A reused key on an invalid entry reports the validation error
- **WHEN** a client posts an unbalanced entry using a key already used by an existing entry
- **THEN** the system responds 422 with the `unbalanced-entry` type, not `idempotency-key-reused`

#### Scenario: A reused key with a malformed account code reports the malformed code
- **WHEN** a client posts an otherwise valid entry whose line has an upper-cased or whitespace-padded `account_code`, using a key already used by an existing entry
- **THEN** the system responds 422 with the `invalid-account-code` type, not `idempotency-key-reused`

#### Scenario: A reused key is reported before an unknown account
- **WHEN** a client posts an otherwise valid entry that names an unknown account, using a key already used by an existing entry with different lines
- **THEN** the system responds 422 with the `idempotency-key-reused` type, not `unknown-account`

#### Scenario: A new key with an unknown account reports the unknown account
- **WHEN** a client posts an otherwise valid entry that names an unknown account, using a key that has never been used
- **THEN** the system responds 422 with the `unknown-account` type

### Requirement: A journal entry has at most 100 lines
The system SHALL reject a journal entry that has more than 100 lines, with 422 Unprocessable Entity and problem `type` `urn:ledger-split-engine:problem:line-limit-exceeded`, whatever its key and whether or not its lines are otherwise valid. The `detail` SHALL state the number of lines submitted and the permitted maximum.

#### Scenario: Entry with 101 lines rejected
- **WHEN** a client submits a balanced entry of 101 non-zero lines on distinct existing accounts
- **THEN** the system responds 422 Unprocessable Entity with the `line-limit-exceeded` type and a detail stating 101 lines and the maximum of 100, and creates nothing

#### Scenario: Entry with 100 lines accepted
- **WHEN** a client submits a balanced entry of exactly 100 non-zero lines on distinct existing accounts
- **THEN** the system accepts the entry and responds 201 Created with the 100 lines numbered 1 to 100

### Requirement: Every line's account code must be well-formed
The system SHALL reject a journal entry containing a line whose `account_code` does not have the shape required of a registered account's code (a lowercase snake_case prefix with an optional `:identifier` suffix, at most 100 characters), with 422 Unprocessable Entity and problem `type` `urn:ledger-split-engine:problem:invalid-account-code`, whatever its key. The code is compared exactly: the system SHALL NOT trim or case-fold it. The `detail` SHALL identify the line and describe the required shape.

#### Scenario: Upper-cased account code rejected
- **WHEN** a client submits an otherwise valid balanced entry in which one line's `account_code` contains uppercase letters
- **THEN** the system responds 422 Unprocessable Entity with the `invalid-account-code` type and a detail naming the line and the required shape, and creates nothing

#### Scenario: Whitespace-padded account code rejected
- **WHEN** a client submits an otherwise valid balanced entry in which one line's `account_code` is a registered code with a leading or trailing space
- **THEN** the system responds 422 Unprocessable Entity with the `invalid-account-code` type and creates nothing, rather than trimming the code and posting

#### Scenario: Well-formed unknown code is not a format error
- **WHEN** a client submits an otherwise valid balanced entry in which one line names a well-formed code that no account has
- **THEN** the system responds 422 with the `unknown-account` type, not `invalid-account-code`
### Requirement: Posting accepts no query parameters
The system SHALL respond 400 Bad Request with `Content-Type: application/problem+json`, naming every query parameter in the detail, to a request to post a journal entry that carries any query parameter, whatever its name or value, and SHALL post nothing and consume no idempotency key for it. A parameter that was silently ignored would be a silently dropped input, the same false success as an unrecognized field in the request body; in particular the idempotency key is a header, and one sent as a query parameter SHALL NOT be mistaken for it. Only the query string is examined: the fields of a form-encoded request body are not query parameters and are never reported as such. A malformed percent-encoding in the query string SHALL likewise be a 400 with `application/problem+json`, never an internal error, and so SHALL a parameter with no name.

#### Scenario: A post with a stray query parameter is rejected and posts nothing
- **WHEN** a client posts a valid entry with an `Idempotency-Key` header and the query string `x=1`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail naming `x`, and no entry exists afterwards

#### Scenario: A rejected post leaves its key unused
- **WHEN** a client posts a valid entry with a key and the query string `x=1`, and then posts the same entry with the same key and no query string
- **THEN** the second request responds 201 Created without the `Idempotent-Replayed` header

#### Scenario: A key sent as a query parameter is not an idempotency key
- **WHEN** a client posts a valid entry with the key as the query parameter `idempotency_key` and no `Idempotency-Key` header
- **THEN** the system responds 400 Bad Request with a detail naming `idempotency_key`, and no entry exists afterwards

#### Scenario: A malformed percent-encoding is a client error
- **WHEN** a client posts an entry with a query string whose percent-encoding is malformed, such as `x=%zz`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json`, not 500 Internal Server Error

#### Scenario: The fields of a form-encoded body are not query parameters
- **WHEN** a client posts to the entry endpoint with the query string `x=1` and a form-encoded body
- **THEN** the system responds 400 Bad Request with a detail naming `x` and none of the body's fields, and posts nothing

#### Scenario: A parameter with no name is a client error
- **WHEN** a client posts an entry with the query string `=1`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail stating that a parameter has no name, not a statement about percent-encoding

### Requirement: A request body has a size limit
The system SHALL read at most 256 KiB (262,144 bytes) of a request body, and SHALL answer a body that goes past the limit with 413 Content Too Large and `Content-Type: application/problem+json` whose detail states the limit; nothing SHALL be posted and no idempotency key consumed for it. The limit applies to the bytes actually read, so it holds for a body that declares no length (a chunked body), and it applies whatever way the request path is spelled: percent-encoded characters in the path or `;` path parameters name the same endpoint and get the same limit. The entry limit of 100 lines is a separate rule and is applied to a body that fits.

#### Scenario: A body over the limit is rejected, posts nothing and leaves its key unused
- **WHEN** a client posts an entry with a body of 262,145 bytes and an `Idempotency-Key`, and then posts the same valid entry, small, with the same key
- **THEN** the first request responds 413 Content Too Large with `Content-Type: application/problem+json` and a detail stating the limit, and the second responds 201 Created without the `Idempotent-Replayed` header

#### Scenario: A body of exactly the limit is read in full
- **WHEN** a client posts an otherwise valid entry with a body of exactly 262,144 bytes
- **THEN** the system responds 201 Created

#### Scenario: A body that declares no length is limited too
- **WHEN** a client posts an entry with a chunked body that goes past the limit
- **THEN** the system responds 413 Content Too Large

#### Scenario: Another spelling of the path gets the same limit
- **WHEN** a client sends a body over the limit to the entry endpoint written as `/api;x=1/v1/journal-entries` or `/api/%761/journal-entries`
- **THEN** the system responds 413 Content Too Large, as it does for `/api/v1/journal-entries`

### Requirement: Error details repeat a bounded part of a rejected value
The system SHALL NOT repeat more than 64 characters of a value it rejected in the detail of an error: a longer value is cut at 64 characters and marked with an ellipsis, so the response stays small however large the request was. A value of 64 characters or fewer is repeated whole. This covers the `account_code` of a line and a malformed `Idempotency-Key`. It does not cover the detail of a key reused for a different request: that key was accepted as well-formed, so it is at most 255 characters, and the detail names it in full.

#### Scenario: A huge account code on a line is not echoed in full
- **WHEN** a client posts an entry in which a line's `account_code` is 200,000 characters long
- **THEN** the system responds 422 Unprocessable Content with the type `urn:ledger-split-engine:problem:invalid-account-code` and a detail that contains the first 64 characters of the code followed by an ellipsis and none of the rest

#### Scenario: A huge idempotency key is not echoed in full
- **WHEN** a client posts an entry with an `Idempotency-Key` that is malformed and long (for example 1,000 characters)
- **THEN** the system responds 400 Bad Request whose detail contains at most the first 64 characters of the key followed by an ellipsis

#### Scenario: A reused key of 255 characters is still named in full
- **WHEN** a client posts an entry with a well-formed key of 255 characters that was already used for a different request
- **THEN** the detail of the 422 Unprocessable Content names the whole key
