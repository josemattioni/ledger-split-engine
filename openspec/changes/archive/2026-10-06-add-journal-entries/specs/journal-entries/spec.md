## Purpose

Lets a client move money through the ledger by posting a balanced, multi-line journal entry against existing accounts, with the validation rules that keep every entry consistent with double-entry bookkeeping and with the engine's single-currency constraint.

## ADDED Requirements

### Requirement: Post a balanced journal entry
The system SHALL allow a client to post a journal entry consisting of a list of lines, each naming an existing account by `account_code` and a signed `amount_cents`, where the lines are inserted together as a single unit. Rejecting degenerate shapes — an entry with no lines, a single line, a zero amount, or the same account twice — is deliberately out of scope for this capability's current requirements; the system does not yet enforce those.

#### Scenario: Successful post
- **WHEN** a client submits a journal entry whose lines reference existing accounts and whose `amount_cents` values sum to zero
- **THEN** the system creates the entry and responds 201 Created with the entry's `entry_id`, `idempotency_key`, `created_at`, and its lines in submitted order, each with `line_number`, `account_code`, and `amount_cents`

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

### Requirement: Every line must reference an existing account
The system SHALL reject a journal entry containing a line whose `account_code` does not match any registered account.

#### Scenario: Unknown account referenced
- **WHEN** a client submits a journal entry with a line whose `account_code` does not match any registered account
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
The system SHALL reject a post whose `Idempotency-Key` matches a key already used by a previously posted journal entry, regardless of whether the new request's lines match the original.

#### Scenario: Repeated idempotency key rejected
- **WHEN** a client posts a journal entry using an `Idempotency-Key` that was already used by a prior successful post
- **THEN** the system responds 409 Conflict and creates no new entry, leaving the original entry unchanged

### Requirement: Malformed post requests are rejected
The system SHALL reject, with 400 Bad Request, a post whose body is not well-formed: missing or unrecognized fields, a line with the wrong JSON type for `account_code` (anything other than a string) or `amount_cents` (anything other than an integer number), or an `amount_cents` value outside the supported integer range. The system SHALL NOT convert a value from one JSON type to another to make it fit.

#### Scenario: Malformed line rejected
- **WHEN** a client submits a journal entry with a line missing `account_code` or `amount_cents`, or with a non-numeric `amount_cents`
- **THEN** the system responds 400 Bad Request and creates nothing

#### Scenario: Wrongly-typed line values are not converted
- **WHEN** a client submits a line whose `amount_cents` is a JSON string (for example `"-100"`), or whose `account_code` is a JSON number or boolean
- **THEN** the system responds 400 Bad Request and creates nothing, rather than converting the value and proceeding

### Requirement: Journal entry identity is server-assigned
The system SHALL assign the `entry_id` returned for a posted journal entry itself; a client-supplied `entry_id` (or any internal database id) SHALL NOT appear in, or be accepted from, the request or response.

#### Scenario: Response carries only server-assigned identity
- **WHEN** a journal entry is successfully posted
- **THEN** the response's `entry_id` is assigned by the system, and no internal database id appears anywhere in the response

### Requirement: Journal entry errors are reported as problem+json
The system SHALL report every error response for posting a journal entry as `application/problem+json`, with a detail describing the violated rule, including the case where the system detects that the balance rule was violated only after accepting a request it should have rejected up front.

#### Scenario: Validation and conflict errors carry a description
- **WHEN** a post is rejected as malformed (400), as violating a ledger rule (422), or as a duplicate idempotency key (409)
- **THEN** the response has `Content-Type: application/problem+json` and a human-readable `detail` describing what was wrong

#### Scenario: Internal balance-check failure still yields a problem response
- **WHEN** a posted entry passes the system's own pre-insert balance check but is later found unbalanced by the storage layer's own backstop check
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail that does not leak internal detail, and logs the failure for investigation
