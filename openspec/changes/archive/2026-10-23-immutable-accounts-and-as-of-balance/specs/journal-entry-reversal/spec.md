## ADDED Requirements

### Requirement: The reversal request accepts no query parameters
The system SHALL respond 400 Bad Request with `Content-Type: application/problem+json`, naming every query parameter in the detail, to a reversal request that carries any query parameter, whatever its name or value, and SHALL reverse nothing and consume no idempotency key for it. A parameter that was silently ignored would be a silently dropped input, like the body the reversal request also refuses: the server builds the reversal and the client cannot shape it through the query string either. A malformed percent-encoding in the query string SHALL likewise be a 400 with `application/problem+json`, never an internal error.

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
