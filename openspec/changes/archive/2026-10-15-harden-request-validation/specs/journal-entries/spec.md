## MODIFIED Requirements

### Requirement: Every line must reference an existing account
The system SHALL reject a journal entry containing a line whose `account_code` is well-formed (see "Every line's account code must be well-formed") but does not match any registered account.

#### Scenario: Unknown account referenced
- **WHEN** a client submits a journal entry with a line whose well-formed `account_code` does not match any registered account
- **THEN** the system responds 422 Unprocessable Entity with a detail identifying the unrecognized `account_code`, and creates nothing

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

### Requirement: Journal entry errors are reported as problem+json
The system SHALL report every error response for posting a journal entry as `application/problem+json`, with a detail describing the violated rule, including the case where the system detects that the balance rule was violated only after accepting a request it should have rejected up front. A 400 SHALL be used only for a request the client got wrong; a failure inside the system SHALL be reported as an internal error, never as a client error.

#### Scenario: Validation and conflict errors carry a description
- **WHEN** a post is rejected as malformed (400) or as violating a ledger rule (422), including reuse of an idempotency key for a different request
- **THEN** the response has `Content-Type: application/problem+json` and a human-readable `detail` describing what was wrong

#### Scenario: Internal balance-check failure still yields a problem response
- **WHEN** a posted entry passes the system's own pre-insert balance check but is later found unbalanced by the storage layer's own backstop check
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail that does not leak internal detail, and logs the failure for investigation

#### Scenario: An internal failure is never reported as a client error
- **WHEN** a valid post fails because a component inside the system rejects an argument it was given
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and the generic detail, not 400 with the component's own message, and logs the failure for investigation

### Requirement: Ledger-rule violations are distinguishable by problem type
The system SHALL report each 422 violation — of a ledger rule or of the idempotency-key rule — with its own stable problem `type`, so that the violated rule can be identified from the `type` and not only from the status code or the wording of `detail`. The types are:

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

## ADDED Requirements

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
