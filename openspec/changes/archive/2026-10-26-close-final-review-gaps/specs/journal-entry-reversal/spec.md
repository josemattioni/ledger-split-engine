## MODIFIED Requirements

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

## ADDED Requirements

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
