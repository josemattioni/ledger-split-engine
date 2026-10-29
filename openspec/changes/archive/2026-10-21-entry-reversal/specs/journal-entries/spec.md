## MODIFIED Requirements

### Requirement: Post a balanced journal entry
The system SHALL allow a client to post a journal entry consisting of a list of lines, each naming an existing account by `account_code` and a signed `amount_cents`, where the lines are inserted together as a single unit. A posted entry is always a real movement of money: it has at least two lines, no line has a zero amount, and no account appears on more than one line (see the requirements below). Every representation of an entry that the system returns includes a `reverses_entry_id`, which is `null` for an entry posted this way: it holds a value only for a reversal (see the `journal-entry-reversal` capability).

#### Scenario: Successful post
- **WHEN** a client submits a journal entry of two or more lines on distinct existing accounts, each with a non-zero `amount_cents`, whose `amount_cents` values sum to zero
- **THEN** the system creates the entry and responds 201 Created with the entry's `entry_id`, `idempotency_key`, `created_at`, a `reverses_entry_id` of `null`, and its lines in submitted order, each with `line_number`, `account_code`, and `amount_cents`

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
