# journal-entry-reversal Specification

## Purpose
Lets a client correct a mistaken journal entry by reversing it: the system posts a new entry that exactly negates the original and links back to it, so the append-only ledger keeps both the mistake and its correction, and every affected account returns to its earlier balance.

## Requirements

### Requirement: A posted entry can be reversed in full
The system SHALL allow a client to reverse a posted journal entry with `POST /api/v1/journal-entries/{entry_id}/reversal`, where `entry_id` is the public identifier of the entry to reverse, with a required `Idempotency-Key` header and no request body. The system SHALL create a new journal entry, the reversal, whose lines have the same `line_number` and `account_code` as the original's lines, in the same order, each with its `amount_cents` negated, and SHALL respond 201 Created with that entry in the same representation as a posted entry. The reversal's `reverses_entry_id` SHALL be the original's `entry_id`. The client never supplies lines: the system builds them. The reversal is a full correction only; a partial correction is an ordinary new entry.

#### Scenario: Successful reversal
- **WHEN** a client posts a two-line entry and then requests its reversal with a key that has not been used
- **THEN** the system responds 201 Created with an entry whose lines are the original's lines at the same `line_number` and `account_code` with each `amount_cents` negated, whose `reverses_entry_id` is the original's `entry_id`, whose `idempotency_key` is the key sent, and whose own `entry_id` differs from the original's

#### Scenario: Every line is negated, in order
- **WHEN** a client reverses an entry of three lines of +9750, -750 and -9000
- **THEN** the reversal's lines are, in the same order and on the same accounts, -9750, +750 and +9000, numbered 1, 2 and 3

#### Scenario: A reversal has its own identity and timestamp
- **WHEN** a client reverses an entry
- **THEN** the reversal's `entry_id` and `created_at` are its own, and its `created_at` is not earlier than the original's

### Requirement: A reversal restores balances like any other entry
The system SHALL treat a reversal as an ordinary entry in every respect except its link to the original: it SHALL count in account balances exactly as any posted entry does, so that after an entry has been reversed every account the entry touched has the balance it had before the entry was posted.

#### Scenario: Balances return to their values before the original
- **WHEN** a client reads the balances of the accounts an entry touches, posts the entry, and then reverses it
- **THEN** each of those balances equals the value read before the entry was posted

#### Scenario: A reversal is counted once
- **WHEN** a client reverses an entry and then retries the reversal with the same key
- **THEN** the balances count the reversal once, so they are exactly the values they had before the original entry

### Requirement: A reversal never changes the original entry
The system SHALL record the link between a reversal and its original on the reversal only. The original entry SHALL remain exactly as first returned, with a `reverses_entry_id` of `null`.

#### Scenario: The original is unchanged after it is reversed
- **WHEN** a client posts an entry, reverses it, and then retries the original post with the same key and the same lines
- **THEN** the system replays the original entry exactly as first returned, with the same body, including a `reverses_entry_id` of `null`

### Requirement: The reversal request carries no body
The system SHALL reject, with 400 Bad Request, a reversal request that has a non-empty body, and SHALL create nothing, so that a client that tries to send lines is never answered by silently reversing the whole entry. A request with no body, or an empty body, is accepted.

#### Scenario: A body with lines is rejected
- **WHEN** a client requests the reversal of an existing entry with a body containing lines
- **THEN** the system responds 400 Bad Request and creates nothing, and the entry is not reversed

#### Scenario: An empty JSON object is also a body
- **WHEN** a client requests a reversal with the body `{}`
- **THEN** the system responds 400 Bad Request and creates nothing

### Requirement: An entry can be reversed at most once
The system SHALL reject, with 422 Unprocessable Entity and problem `type` `urn:ledger-split-engine:problem:entry-already-reversed`, a request to reverse an entry that already has a reversal, when the request's key is not the key of that reversal. The `detail` SHALL name the `entry_id` of the entry that was to be reversed. The system SHALL create nothing, and the existing reversal SHALL be unchanged.

#### Scenario: A second reversal under a new key is rejected
- **WHEN** a client reverses an entry and then requests its reversal again with a different key
- **THEN** the system responds 422 with the `entry-already-reversed` type and a detail naming the original's `entry_id`, and creates nothing

#### Scenario: A rejected second reversal leaves the balances alone
- **WHEN** a second reversal of an entry is rejected
- **THEN** the balances of the accounts involved are the same as they were before the rejected request

### Requirement: A reversal cannot itself be reversed
The system SHALL reject, with 422 Unprocessable Entity and problem `type` `urn:ledger-split-engine:problem:cannot-reverse-a-reversal`, a request to reverse an entry that is itself a reversal. The `detail` SHALL name the `entry_id` of that entry and SHALL say that a reversal is undone by posting the original movement again as an ordinary entry. The system SHALL create nothing.

#### Scenario: Reversing a reversal is rejected
- **WHEN** a client reverses an entry and then requests the reversal of the reversal
- **THEN** the system responds 422 with the `cannot-reverse-a-reversal` type, and creates nothing

#### Scenario: A reversal is undone by posting the original movement again
- **WHEN** a client reverses an entry and then posts the original's lines again as an ordinary entry with a new key
- **THEN** the system responds 201 Created with a `reverses_entry_id` of `null`, and the balances of the accounts involved equal what they were just after the original was first posted

### Requirement: The entry to reverse must exist and be identified by a well-formed entry_id
The system SHALL respond 404 Not Found with `application/problem+json` to a reversal request whose `entry_id` is well-formed but matches no entry, and SHALL create nothing. The system SHALL respond 400 Bad Request to a reversal request whose `entry_id` is not a UUID in its canonical form of 8-4-4-4-12 hexadecimal digits separated by hyphens, with either letter case accepted. The system SHALL NOT trim, reshape or complete a malformed `entry_id` to make it fit.

#### Scenario: An unknown entry_id is a 404
- **WHEN** a client requests the reversal of a well-formed `entry_id` that no entry has
- **THEN** the system responds 404 Not Found with `Content-Type: application/problem+json`, and creates nothing

#### Scenario: A value that is not a UUID is a 400
- **WHEN** a client requests a reversal with the path value `not-a-uuid`
- **THEN** the system responds 400 Bad Request and creates nothing

#### Scenario: A non-canonical UUID is a 400
- **WHEN** a client requests a reversal with an `entry_id` written without hyphens, as `1-1-1-1-1`, or with surrounding whitespace
- **THEN** the system responds 400 Bad Request and creates nothing, rather than reshaping the value into a UUID

#### Scenario: Upper-case hexadecimal digits are accepted
- **WHEN** a client requests the reversal of an existing entry using its `entry_id` written with upper-case hexadecimal digits
- **THEN** the system reverses that entry and responds 201 Created

### Requirement: A reversal requires an idempotency key
The system SHALL require an `Idempotency-Key` request header on every reversal request, with the same shape and the same single-occurrence rule as when posting an entry: exactly one header, of 1 to 255 visible ASCII characters. The system SHALL reject a request that lacks one, or whose value does not meet that shape, with 400 Bad Request, and SHALL create nothing.

#### Scenario: Missing idempotency key rejected
- **WHEN** a client requests the reversal of an existing entry without an `Idempotency-Key` header
- **THEN** the system responds 400 Bad Request and creates nothing

#### Scenario: Malformed idempotency key rejected
- **WHEN** a client requests a reversal with an `Idempotency-Key` that is empty, longer than 255 characters, or contains a character other than a visible ASCII character
- **THEN** the system responds 400 Bad Request and creates nothing

#### Scenario: Repeated idempotency key header rejected
- **WHEN** a client requests a reversal with more than one `Idempotency-Key` header
- **THEN** the system responds 400 Bad Request and creates nothing

### Requirement: A retried reversal is replayed
The system SHALL answer a reversal request whose `Idempotency-Key` is the key of the reversal already created for that same entry with 201 Created, the reversal exactly as first returned, and the response header `Idempotent-Replayed: true`, and SHALL write nothing. The header SHALL be present on a replay and only on a replay. Such a retry SHALL NOT be reported as `entry-already-reversed`.

#### Scenario: An identical retry replays the reversal
- **WHEN** a client reverses an entry and then requests the same reversal again with the same key
- **THEN** both responses are 201 Created with identical bodies, including the same `entry_id`, `created_at` and `reverses_entry_id`, and only the second carries `Idempotent-Replayed: true`

#### Scenario: A retry of a successful reversal never reports entry-already-reversed
- **WHEN** a client reverses an entry, so that the entry now has a reversal, and then retries the request with the same key
- **THEN** the system responds 201 Created with `Idempotent-Replayed: true`, not 422

#### Scenario: A first reversal carries no replay header
- **WHEN** a client reverses an entry with a key that has never been used
- **THEN** the response is 201 Created and has no `Idempotent-Replayed` header

### Requirement: A key reused for a different request is rejected for reversals
The system SHALL reject, with 422 Unprocessable Entity and problem `type` `urn:ledger-split-engine:problem:idempotency-key-reused`, a reversal request whose key matches an existing entry that was not created by the same reversal request, and SHALL reject in the same way an ordinary post whose key matches a reversal. The `detail` SHALL name the key and SHALL NOT reveal the stored entry's lines. The system SHALL create nothing.

#### Scenario: A key used by an ordinary entry is rejected for a reversal
- **WHEN** a client posts an ordinary entry with key K and then requests the reversal of a different, existing entry with key K
- **THEN** the system responds 422 with the `idempotency-key-reused` type, and creates nothing

#### Scenario: A key used by the reversal of another entry is rejected
- **WHEN** a client reverses entry A with key K and then requests the reversal of entry B with key K
- **THEN** the system responds 422 with the `idempotency-key-reused` type, and entry B is not reversed

#### Scenario: A key used by a reversal cannot post an ordinary entry
- **WHEN** a client reverses an entry with key K and then posts an ordinary entry with key K, even with lines identical to the reversal's
- **THEN** the system responds 422 with the `idempotency-key-reused` type, and creates nothing

### Requirement: A reversal request is evaluated in a fixed order
The system SHALL evaluate a reversal request in this order: first, whether the request is well-formed — the `entry_id` in the path, the `Idempotency-Key`, and the absence of a body (400); second, whether the entry to reverse exists (404); third, whether that entry is itself a reversal (422 `cannot-reverse-a-reversal`); fourth, the idempotency key, answering a retry from what is stored and rejecting a reuse; fifth, whether the entry has already been reversed (422 `entry-already-reversed`). The first problem found in that order is the one reported.

#### Scenario: A malformed key is reported before a missing entry
- **WHEN** a client requests the reversal of an unknown entry with a malformed `Idempotency-Key`
- **THEN** the system responds 400 Bad Request, not 404

#### Scenario: A reused key with an unknown entry reports the missing entry
- **WHEN** a client requests the reversal of an unknown entry with a key already used by an existing entry
- **THEN** the system responds 404 Not Found, not 422

#### Scenario: A reused key with an existing entry reports the key reuse
- **WHEN** a client requests the reversal of an existing, not yet reversed entry with a key already used by an ordinary entry
- **THEN** the system responds 422 with the `idempotency-key-reused` type

#### Scenario: A reversal of a reversal is reported before the key is considered
- **WHEN** a client requests the reversal of an entry that is itself a reversal, using the key that entry was created with
- **THEN** the system responds 422 with the `cannot-reverse-a-reversal` type, not a replay

#### Scenario: A reused key is reported before an already-reversed entry
- **WHEN** a client requests the reversal of an entry that already has a reversal, using a key already used by an unrelated ordinary entry
- **THEN** the system responds 422 with the `idempotency-key-reused` type, not `entry-already-reversed`

### Requirement: Concurrent reversals of one entry produce exactly one reversal
The system SHALL, when several reversal requests for the same entry are in flight at the same time, create exactly one reversal. With different keys, exactly one request SHALL succeed and each other SHALL be answered with `entry-already-reversed`. With the same key, exactly one reversal SHALL be created and each other request SHALL be answered with the replay of it. Each other request waits for the first to finish and is then answered as if it had arrived afterwards; no intermediate "in progress" response is visible to clients. If the first request fails and leaves no reversal, a waiting request proceeds as a new request.

#### Scenario: Simultaneous reversals under different keys
- **WHEN** two clients request the reversal of the same entry at the same time with different keys
- **THEN** exactly one receives 201 Created, the other receives 422 with the `entry-already-reversed` type, and exactly one reversal of that entry exists

#### Scenario: Simultaneous retries under the same key
- **WHEN** two clients request the reversal of the same entry at the same time with the same key
- **THEN** both receive 201 Created with identical bodies, exactly one carries `Idempotent-Replayed: true`, and exactly one reversal exists

### Requirement: A rejected reversal does not consume its key
The system SHALL treat an `Idempotency-Key` as used by a reversal request only if that request succeeded. A reversal request rejected for any reason, or that fails with a server error, SHALL leave its key unused.

#### Scenario: A key from a rejected second reversal is reusable
- **WHEN** a client's reversal of an already reversed entry is rejected with 422 using key K, and the client then posts a valid ordinary entry with key K
- **THEN** the post responds 201 Created with no `Idempotent-Replayed` header

#### Scenario: A key from a reversal of an unknown entry is reusable
- **WHEN** a client's reversal of an unknown entry is rejected with 404 using key K, and the client then reverses an existing entry with key K
- **THEN** the reversal responds 201 Created with no `Idempotent-Replayed` header

### Requirement: Reversal errors are reported as problem+json
The system SHALL report every error response for a reversal request as `application/problem+json` with a human-readable `detail` describing what was wrong, and SHALL NOT answer a reversal request with 409 Conflict. A failure inside the system SHALL be reported as an internal error with a generic detail, never as a client error. The one exception is a request that the HTTP server itself rejects before the application receives it, such as one whose `Idempotency-Key` header contains a control character like DEL (0x7F) or 0x01: the server answers 400 Bad Request with its own error page, which is not `application/problem+json`, and nothing is created. A character the application does receive (a space, a tab or a non-ASCII byte in that header) is answered with `application/problem+json` as above.

#### Scenario: Reversal errors carry a description
- **WHEN** a reversal request is rejected as malformed (400), as naming an unknown entry (404), or as violating a reversal rule or the idempotency-key rule (422)
- **THEN** the response has `Content-Type: application/problem+json` and a human-readable `detail`

#### Scenario: A reversal is never answered with 409
- **WHEN** a client requests the reversal of an entry that already has a reversal, or reuses a key
- **THEN** the system responds 422, not 409 Conflict

#### Scenario: A request the HTTP server rejects before the application sees it is still a 400
- **WHEN** a client requests a reversal with an `Idempotency-Key` header containing a control character such as DEL (0x7F) or 0x01
- **THEN** the HTTP server responds 400 Bad Request with its own error page rather than `application/problem+json`, and the entry is not reversed
### Requirement: The reversal request accepts no query parameters
The system SHALL respond 400 Bad Request with `Content-Type: application/problem+json`, naming every query parameter in the detail, to a reversal request that carries any query parameter, whatever its name or value, and SHALL reverse nothing and consume no idempotency key for it. A parameter that was silently ignored would be a silently dropped input, like the body the reversal request also refuses: the server builds the reversal and the client cannot shape it through the query string either. Only the query string is examined: the fields of a form-encoded request body are not query parameters and are never reported as such. A malformed percent-encoding in the query string SHALL likewise be a 400 with `application/problem+json`, never an internal error, and so SHALL a parameter with no name.

#### Scenario: A reversal with a stray query parameter is rejected and reverses nothing
- **WHEN** a client reverses an entry that can be reversed, with an `Idempotency-Key` header and the query string `x=1`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail naming `x`, and the entry is not reversed

#### Scenario: A rejected reversal leaves its key unused
- **WHEN** a client reverses an entry with a key and the query string `x=1`, and then reverses the same entry with the same key and no query string
- **THEN** the second request responds 201 Created without the `Idempotent-Replayed` header

#### Scenario: A link to the original sent as a query parameter is rejected
- **WHEN** a client reverses an entry with the query parameter `reverses_entry_id`
- **THEN** the system responds 400 Bad Request with a detail naming `reverses_entry_id`

#### Scenario: A malformed percent-encoding is a client error
- **WHEN** a client reverses an entry with a query string whose percent-encoding is malformed, such as `x=%zz`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json`, not 500 Internal Server Error

#### Scenario: The fields of a form-encoded body are not query parameters
- **WHEN** a client reverses an entry with the query string `x=1` and a form-encoded body
- **THEN** the system responds 400 Bad Request with a detail naming `x` and none of the body's fields, and the entry is not reversed

#### Scenario: A parameter with no name is a client error
- **WHEN** a client reverses an entry with the query string `=1`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail stating that a parameter has no name, not a statement about percent-encoding

### Requirement: A request body has a size limit
The system SHALL read at most 256 KiB (262,144 bytes) of a request body, whatever way the request path is spelled (percent-encoded characters in the path or `;` path parameters name the same endpoint and get the same limit), and SHALL answer a reversal request whose body is declared larger than the limit with 413 Content Too Large and `Content-Type: application/problem+json` whose detail states the limit; nothing SHALL be reversed and no idempotency key consumed for it. A reversal request has no body, and a body of any smaller size is the ordinary 400 for having one; a body that declares no length (a chunked body) is judged by its first byte, which is enough to show that it is not empty.

#### Scenario: A body declared over the limit is a 413 and reverses nothing
- **WHEN** a client sends a reversal request with a body of 262,145 bytes
- **THEN** the system responds 413 Content Too Large with `Content-Type: application/problem+json` and a detail stating the limit, and the entry is not reversed

#### Scenario: A small body is still the ordinary 400
- **WHEN** a client sends a reversal request with a body of two bytes
- **THEN** the system responds 400 Bad Request with a detail stating that a reversal request must not have a body

#### Scenario: Another spelling of the path gets the same limit
- **WHEN** a client sends a body over the limit to the reversal endpoint written with a `;` path parameter, such as `/api;x/v1/journal-entries/{entry_id}/reversal`
- **THEN** the system responds 413 Content Too Large, as it does for `/api/v1/journal-entries/{entry_id}/reversal`

### Requirement: Error details repeat a bounded part of a rejected value
The system SHALL NOT repeat more than 64 characters of a value it rejected in the detail of an error: a longer value is cut at 64 characters and marked with an ellipsis, so the response stays small however large the request was. A value of 64 characters or fewer is repeated whole. This covers a malformed `entry_id` in the path and a malformed `Idempotency-Key`.

#### Scenario: A huge malformed entry_id is not echoed in full
- **WHEN** a client reverses an entry whose `entry_id` in the path is malformed and thousands of characters long
- **THEN** the system responds 400 Bad Request whose detail contains the first 64 characters of the value followed by an ellipsis and none of the rest

#### Scenario: A short malformed entry_id is still named in full
- **WHEN** a client reverses an entry whose `entry_id` is `not-a-uuid`
- **THEN** the detail of the 400 Bad Request names `not-a-uuid`
