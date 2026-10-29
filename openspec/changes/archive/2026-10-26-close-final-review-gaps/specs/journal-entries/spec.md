## MODIFIED Requirements

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

## ADDED Requirements

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
