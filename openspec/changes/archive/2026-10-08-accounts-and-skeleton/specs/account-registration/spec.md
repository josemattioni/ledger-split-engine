## Purpose

Lets the settlement engine register and look up the ledger accounts that later journal entries will post against, identified by a unique human-readable code rather than an internal id.

## ADDED Requirements

### Requirement: Register an account
The system SHALL allow a client to register a new ledger account by submitting a unique `code`, an accounting `type`, and an optional `owner_ref`.

#### Scenario: Successful registration
- **WHEN** a client submits a registration request with a valid, unused `code` and a valid `type`
- **THEN** the system creates the account, assigns it the fixed settlement currency, and responds 201 Created with a `Location` header pointing to the new account's `code`

#### Scenario: Registration without an owner reference
- **WHEN** a client submits a registration request with a valid `code` and `type` and omits `owner_ref`
- **THEN** the system creates the account with no owner reference

### Requirement: Account code uniqueness
The system SHALL reject registration of an account whose `code` already exists.

#### Scenario: Duplicate code
- **WHEN** a client submits a registration request whose `code` matches an existing account
- **THEN** the system responds 409 Conflict and does not create a second account

### Requirement: Account code format
The system SHALL accept only account codes matching a lowercase snake_case prefix with an optional `:identifier` suffix, and SHALL reject any other code with an error describing the required shape.

#### Scenario: Malformed code rejected
- **WHEN** a client submits a registration request whose `code` does not match the required shape (for example, it contains uppercase letters, spaces, or starts with a digit)
- **THEN** the system responds 400 Bad Request with an error message describing the required code shape, and does not create an account

#### Scenario: Well-formed codes accepted
- **WHEN** a client submits a registration request with a code such as `marketplace_commission`, `seller_payable:123`, or `acquirer_fee`
- **THEN** the system accepts the code as valid

### Requirement: Account type validation
The system SHALL accept only one of the fixed accounting classifications `ASSET`, `LIABILITY`, `REVENUE`, or `EXPENSE` as an account's `type`, and SHALL reject any other value.

#### Scenario: Invalid type rejected
- **WHEN** a client submits a registration request with a `type` outside the fixed set of accounting classifications
- **THEN** the system responds 400 Bad Request with an error message describing the allowed values, and does not create an account

### Requirement: Owner reference validation
The system SHALL treat `owner_ref` as optional and opaque in format: omitting it or providing `null` means the account has no owner. A non-null `owner_ref` SHALL be rejected unless it is non-blank, has no leading or trailing whitespace, and is at most 100 characters, since neither an empty/whitespace-only value nor a whitespace-padded value is an equivalent way of expressing the same reference, and a value the system cannot store is not a usable one.

#### Scenario: Blank owner reference rejected
- **WHEN** a client submits a registration request with `owner_ref` present but empty or containing only whitespace
- **THEN** the system responds 400 Bad Request with an error message stating that `owner_ref` must be either omitted/null or non-blank, and does not create an account

#### Scenario: Whitespace-padded owner reference rejected
- **WHEN** a client submits a registration request with `owner_ref` containing leading or trailing whitespace (for example `" 123 "`)
- **THEN** the system responds 400 Bad Request with an error message stating that `owner_ref` must not have leading or trailing whitespace, and does not create an account — the value is rejected rather than trimmed and stored

#### Scenario: Over-length owner reference rejected
- **WHEN** a client submits a registration request with `owner_ref` longer than 100 characters
- **THEN** the system responds 400 Bad Request with an error message describing the length limit, and does not create an account

### Requirement: Fixed settlement currency
The system SHALL assign every registered account the engine's single supported settlement currency; the currency is not a client-supplied input.

#### Scenario: Currency always assigned
- **WHEN** an account is successfully registered
- **THEN** the account's currency is the engine's supported currency, regardless of any value the client may have sent

### Requirement: Look up an account by code
The system SHALL allow a client to retrieve a registered account's details by its `code`.

#### Scenario: Existing account found
- **WHEN** a client requests an account by a `code` that has been registered
- **THEN** the system responds 200 OK with the account's `code`, `type`, `owner_ref`, `currency`, and registration timestamp

#### Scenario: Unknown code
- **WHEN** a client requests an account by a `code` that has not been registered
- **THEN** the system responds 404 Not Found with `Content-Type: application/problem+json` and a detail describing that no account exists for that code

### Requirement: Internal identity is not exposed
The system SHALL NOT include any internal surrogate identifier for an account in API responses or resource URLs; `code` is the only externally visible identifier.

#### Scenario: Response omits internal id
- **WHEN** a client registers or retrieves an account
- **THEN** the response body and the `Location` header reference the account only by its `code`

### Requirement: Errors reported as problem+json
The system SHALL report all error responses for account registration and lookup as `application/problem+json`, with a message that describes the violated rule rather than a generic failure. This applies uniformly, including to responses the framework generates by default (e.g. a 404 from a plain not-found result) — no error path is exempt.

#### Scenario: Validation error body
- **WHEN** a request is rejected for a malformed `code`, an invalid `type`, a blank or padded `owner_ref`, an unrecognized field, or a duplicate `code`
- **THEN** the response has `Content-Type: application/problem+json` and includes a human-readable detail describing what was wrong

#### Scenario: Lookup error body
- **WHEN** a lookup is rejected because the requested `code` does not exist
- **THEN** the response has `Content-Type: application/problem+json`, not a bare status code with no body

#### Scenario: Unexpected failure body
- **WHEN** a request fails for a reason outside the validation and business-rule cases above (for example, the database is unreachable)
- **THEN** the system responds 500 with `Content-Type: application/problem+json` and a generic detail that does not include the underlying exception's message or any other internal detail

### Requirement: Request field names are exact and snake_case
The system SHALL accept request field names in snake_case (matching the database column names, e.g. `owner_ref`), and SHALL reject a request containing a field name it does not recognize rather than silently ignoring it. A differently-cased spelling of a known field (e.g. `ownerRef` instead of `owner_ref`) is an unrecognized field, not an accepted alias.

#### Scenario: Unrecognized field rejected
- **WHEN** a client submits a registration request containing a field name the system does not recognize, including a differently-cased spelling of a real field
- **THEN** the system responds 400 Bad Request and does not create an account, rather than silently ignoring the field and proceeding as if it had not been sent
