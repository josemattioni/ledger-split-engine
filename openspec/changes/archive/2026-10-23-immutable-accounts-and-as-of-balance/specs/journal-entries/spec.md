## ADDED Requirements

### Requirement: Posting accepts no query parameters
The system SHALL respond 400 Bad Request with `Content-Type: application/problem+json`, naming every query parameter in the detail, to a request to post a journal entry that carries any query parameter, whatever its name or value, and SHALL post nothing and consume no idempotency key for it. A parameter that was silently ignored would be a silently dropped input, the same false success as an unrecognized field in the request body; in particular the idempotency key is a header, and one sent as a query parameter SHALL NOT be mistaken for it. A malformed percent-encoding in the query string SHALL likewise be a 400 with `application/problem+json`, never an internal error.

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
