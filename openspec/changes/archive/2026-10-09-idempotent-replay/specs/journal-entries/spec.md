## MODIFIED Requirements

### Requirement: Idempotency keys are unique
The system SHALL ensure that an `Idempotency-Key` identifies at most one journal entry, for as long as that entry exists — keys never expire. A post whose key matches an existing entry and whose lines are identical to that entry's is a retry: the system SHALL respond 201 Created with the original entry exactly as first returned (the same `entry_id`, `idempotency_key`, `created_at`, and lines in `line_number` order) and the response header `Idempotent-Replayed: true`, and SHALL write nothing. The header SHALL be present on a replay and only on a replay. A post whose key matches an existing entry but whose lines differ is a reuse of the key and is rejected (see "A key reused for a different request is rejected"). A repeated key is never answered with 409 Conflict.

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

### Requirement: Journal entry errors are reported as problem+json
The system SHALL report every error response for posting a journal entry as `application/problem+json`, with a detail describing the violated rule, including the case where the system detects that the balance rule was violated only after accepting a request it should have rejected up front.

#### Scenario: Validation and conflict errors carry a description
- **WHEN** a post is rejected as malformed (400) or as violating a ledger rule (422), including reuse of an idempotency key for a different request
- **THEN** the response has `Content-Type: application/problem+json` and a human-readable `detail` describing what was wrong

#### Scenario: Internal balance-check failure still yields a problem response
- **WHEN** a posted entry passes the system's own pre-insert balance check but is later found unbalanced by the storage layer's own backstop check
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail that does not leak internal detail, and logs the failure for investigation

### Requirement: Ledger-rule violations are distinguishable by problem type
The system SHALL report each 422 violation — of a ledger rule or of the idempotency-key rule — with its own stable problem `type`, so that the violated rule can be identified from the `type` and not only from the status code or the wording of `detail`. The types are:

| Rule | Problem `type` |
|---|---|
| Entry does not balance (including an overflowing sum) | `urn:ledger-split-engine:problem:unbalanced-entry` |
| Degenerate entry (fewer than two lines, a zero-amount line, or a repeated account) | `urn:ledger-split-engine:problem:degenerate-entry` |
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

#### Scenario: Unknown account reports the unknown-account type
- **WHEN** a client submits an otherwise valid balanced entry in which one line names an account that does not exist
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

### Requirement: Storage-layer backstop failures are internal errors
The system SHALL treat a violation of any of the storage layer's own entry-shape safeguards (an entry with fewer than two lines, a zero-amount line, an account repeated within an entry) or append-only safeguard as an internal error — 500 Internal Server Error with `application/problem+json`, a generic detail that does not leak internal detail, and an error-level log entry — and SHALL NOT report any of them as a conflict or a client error. Posting a journal entry is never answered with 409 Conflict.

#### Scenario: Entry-shape backstop failure yields a generic 500
- **WHEN** a posted entry passes the system's own pre-insert checks but the storage layer's own safeguard then rejects it
- **THEN** the system responds 500 Internal Server Error with `Content-Type: application/problem+json` and a generic detail, and logs the failure for investigation

#### Scenario: A repeated account is never reported as a repeated idempotency key
- **WHEN** the storage layer's own safeguard rejects a line because its account already appears in the same entry
- **THEN** the system does not respond 409 Conflict; it responds 500, the same as any other backstop failure, because posting never produces a 409

## ADDED Requirements

### Requirement: A key reused for a different request is rejected
The system SHALL reject, with 422 Unprocessable Entity and problem `type` `urn:ledger-split-engine:problem:idempotency-key-reused`, a post whose `Idempotency-Key` matches an existing journal entry but whose lines are not identical to that entry's. Lines are identical only if they have the same count and the same `account_code` and `amount_cents` at every `line_number`; the comparison is order-sensitive, so the same lines in a different order are a different request. The `detail` SHALL name the key and SHALL NOT reveal the stored entry's lines or amounts. The system SHALL write nothing, and the original entry SHALL be unchanged.

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
The system SHALL evaluate a post in this order: first, whether the request is well-formed (400) and whether the entry satisfies the rules that need no stored data — shape, degenerate entry, amount limit, balance (422) — regardless of its key; second, the idempotency key, answering a retry from what is stored; third, and only for a key not yet used, whether the accounts exist and share one currency (422). A retry SHALL therefore be answered from the stored entry without depending on anything else, and a reused key that also names an unknown account SHALL report `idempotency-key-reused`.

#### Scenario: A reused key on an invalid entry reports the validation error
- **WHEN** a client posts an unbalanced entry using a key already used by an existing entry
- **THEN** the system responds 422 with the `unbalanced-entry` type, not `idempotency-key-reused`

#### Scenario: A reused key is reported before an unknown account
- **WHEN** a client posts an otherwise valid entry that names an unknown account, using a key already used by an existing entry with different lines
- **THEN** the system responds 422 with the `idempotency-key-reused` type, not `unknown-account`

#### Scenario: A new key with an unknown account reports the unknown account
- **WHEN** a client posts an otherwise valid entry that names an unknown account, using a key that has never been used
- **THEN** the system responds 422 with the `unknown-account` type
