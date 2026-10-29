## ADDED Requirements

### Requirement: The account endpoints accept no query parameters
The system SHALL respond 400 Bad Request with `Content-Type: application/problem+json`, naming every query parameter in the detail, to a request to register an account or to look one up that carries any query parameter, whatever its name or value, and SHALL register nothing for a rejected registration. A parameter that was silently ignored would be a silently dropped input, the same false success as an unrecognized field in the request body. A malformed percent-encoding in the query string SHALL likewise be a 400 with `application/problem+json`, never an internal error. The check precedes the account lookup. The only endpoint that accepts a query parameter is the balance read, and only `as_of` (see `account-balances`).

#### Scenario: A registration with a stray query parameter is rejected and registers nothing
- **WHEN** a client registers an account with a valid body and the query string `x=1`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail naming `x`, and no account with that code exists afterwards

#### Scenario: A lookup with a stray query parameter is rejected
- **WHEN** a client looks up a registered account with the query string `x=1`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail naming `x`, not 200 OK with the account

#### Scenario: as_of is not accepted on an account lookup
- **WHEN** a client looks up a registered account with `as_of` and a valid instant
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail naming `as_of`

#### Scenario: A stray parameter is reported before an unknown account
- **WHEN** a client looks up an unregistered `code` with the query string `x=1`
- **THEN** the system responds 400 Bad Request, not 404 Not Found

#### Scenario: A malformed percent-encoding is a client error
- **WHEN** a client registers or looks up an account with a query string whose percent-encoding is malformed, such as `x=%zz`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json`, not 500 Internal Server Error
