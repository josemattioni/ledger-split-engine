## MODIFIED Requirements

### Requirement: Post a balanced journal entry
The system SHALL allow a client to post a journal entry consisting of a list of lines, each naming an existing account by `account_code` and a signed `amount_cents`, where the lines are inserted together as a single unit. A posted entry is always a real movement of money: it has at least two lines, no line has a zero amount, and no account appears on more than one line (see the requirements below).

#### Scenario: Successful post
- **WHEN** a client submits a journal entry of two or more lines on distinct existing accounts, each with a non-zero `amount_cents`, whose `amount_cents` values sum to zero
- **THEN** the system creates the entry and responds 201 Created with the entry's `entry_id`, `idempotency_key`, `created_at`, and its lines in submitted order, each with `line_number`, `account_code`, and `amount_cents`

## ADDED Requirements

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

### Requirement: Ledger-rule violations are distinguishable by problem type
The system SHALL report each 422 ledger-rule violation with its own stable problem `type`, so that the violated rule can be identified from the `type` and not only from the status code or the wording of `detail`. The types are:

| Rule | Problem `type` |
|---|---|
| Entry does not balance (including an overflowing sum) | `urn:ledger-split-engine:problem:unbalanced-entry` |
| Degenerate entry (fewer than two lines, a zero-amount line, or a repeated account) | `urn:ledger-split-engine:problem:degenerate-entry` |
| Line references an unknown account | `urn:ledger-split-engine:problem:unknown-account` |
| Lines span more than one currency | `urn:ledger-split-engine:problem:mixed-currency` |
| Line amount outside the permitted range | `urn:ledger-split-engine:problem:amount-limit-exceeded` |

The three degenerate-entry cases share one type; the `detail` states which of them applies. No two rules share a type.

#### Scenario: Unbalanced entry reports the unbalanced-entry type
- **WHEN** a client submits two valid lines of +100 and -99 on distinct existing accounts
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:unbalanced-entry`

#### Scenario: Degenerate entry reports the degenerate-entry type and says which case
- **WHEN** a client submits an otherwise valid entry that is degenerate in exactly one way — an empty `lines` array, a zero-amount line, or a repeated account
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:degenerate-entry` and a `detail` that names that specific case

#### Scenario: Unknown account reports the unknown-account type
- **WHEN** a client submits an otherwise valid balanced entry in which one line names an account that does not exist
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:unknown-account`

#### Scenario: Over-limit amount reports the amount-limit-exceeded type
- **WHEN** a client submits an otherwise valid balanced entry containing a line whose `amount_cents` exceeds the permitted range
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:amount-limit-exceeded`

#### Scenario: Mixed currencies report the mixed-currency type
- **WHEN** a client submits an otherwise valid balanced entry whose lines reference accounts of more than one currency
- **THEN** the system responds 422 Unprocessable Entity with problem `type` `urn:ledger-split-engine:problem:mixed-currency`

### Requirement: Storage-layer backstop failures are internal errors
The system SHALL treat a violation of any of the storage layer's own entry-shape safeguards (an entry with fewer than two lines, a zero-amount line, an account repeated within an entry) or append-only safeguard as an internal error — 500 Internal Server Error with `application/problem+json`, a generic detail that does not leak internal detail, and an error-level log entry — and SHALL NOT report any of them as a conflict or a client error. Only a repeated `Idempotency-Key` is reported as 409 Conflict.

#### Scenario: Entry-shape backstop failure yields a generic 500
- **WHEN** a posted entry passes the system's own pre-insert checks but the storage layer's own safeguard then rejects it
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail, and logs the failure for investigation

#### Scenario: A repeated account is never reported as a repeated idempotency key
- **WHEN** the storage layer's own safeguard rejects a line because its account already appears in the same entry
- **THEN** the system does not respond 409 Conflict; a 409 is reserved for a repeated `Idempotency-Key`
