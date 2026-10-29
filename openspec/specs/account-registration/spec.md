# account-registration Specification

## Purpose
Lets the settlement engine register and look up the ledger accounts that later journal entries will post against, identified by a unique human-readable code rather than an internal id.

## Requirements

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
The system SHALL accept only one of the fixed accounting classifications `ASSET`, `LIABILITY`, `REVENUE`, or `EXPENSE` as an account's `type`, written as that name, and SHALL reject any other value. A position in the list of classifications is not a name, whether it arrives as a JSON number or as a string holding a number.

#### Scenario: Invalid type rejected
- **WHEN** a client submits a registration request with a `type` outside the fixed set of accounting classifications
- **THEN** the system responds 400 Bad Request with an error message describing the allowed values, and does not create an account

#### Scenario: Type given as a quoted index rejected
- **WHEN** a client submits a registration request whose `type` is a string holding a number, such as `"0"` or `"3"`
- **THEN** the system responds 400 Bad Request and does not create an account, rather than treating the number as a position in the list of classifications

### Requirement: Owner reference validation
The system SHALL treat `owner_ref` as optional and opaque in format: omitting it or providing `null` means the account has no owner. A non-null `owner_ref` SHALL be rejected unless it is non-blank, has no leading or trailing whitespace, is at most 100 characters, and is text the system can store exactly as sent. Whitespace means Unicode whitespace: a no-break space, an em space or an ideographic space pads a value just as an ordinary space does. Length is counted in characters (one emoji is one character), not in UTF-16 units. Text that cannot be stored as sent, because it contains the NUL character or an unpaired surrogate, SHALL be rejected as a client error rather than failing inside the database or being stored altered. Neither an empty/whitespace-only value nor a whitespace-padded value is an equivalent way of expressing the same reference, and a value the system cannot store is not a usable one.

#### Scenario: Blank owner reference rejected
- **WHEN** a client submits a registration request with `owner_ref` present but empty or containing only whitespace
- **THEN** the system responds 400 Bad Request with an error message stating that `owner_ref` must be either omitted/null or non-blank, and does not create an account

#### Scenario: Whitespace-padded owner reference rejected
- **WHEN** a client submits a registration request with `owner_ref` containing leading or trailing whitespace (for example `" 123 "`)
- **THEN** the system responds 400 Bad Request with an error message stating that `owner_ref` must not have leading or trailing whitespace, and does not create an account — the value is rejected rather than trimmed and stored

#### Scenario: Owner reference padded with Unicode whitespace rejected
- **WHEN** a client submits a registration request with an `owner_ref` that begins or ends with a no-break space, an em space or an ideographic space
- **THEN** the system responds 400 Bad Request with an error message stating that `owner_ref` must not have leading or trailing whitespace, and does not create an account; the same character inside the value is accepted

#### Scenario: Over-length owner reference rejected
- **WHEN** a client submits a registration request with `owner_ref` longer than 100 characters
- **THEN** the system responds 400 Bad Request with an error message describing the length limit, and does not create an account

#### Scenario: Owner reference of 100 characters outside the basic plane accepted
- **WHEN** a client submits a registration request with an `owner_ref` of exactly 100 emoji
- **THEN** the system responds 201 Created and a later lookup returns the `owner_ref` unchanged

#### Scenario: Owner reference of 101 characters outside the basic plane rejected
- **WHEN** a client submits a registration request with an `owner_ref` of 101 emoji
- **THEN** the system responds 400 Bad Request with an error message describing the length limit, and does not create an account

#### Scenario: Owner reference the database cannot keep as sent rejected
- **WHEN** a client submits a registration request with an `owner_ref` containing the NUL character, or a surrogate that is not part of a valid pair
- **THEN** the system responds 400 Bad Request with an error message stating that the value could not be stored as sent, not 500 Internal Server Error, and does not create an account

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
The system SHALL accept request field names in snake_case (matching the database column names, e.g. `owner_ref`), and SHALL reject a request containing a field name it does not recognize rather than silently ignoring it. A differently-cased spelling of a known field (e.g. `ownerRef` instead of `owner_ref`) is an unrecognized field, not an accepted alias. A field given more than once in the same request SHALL be rejected rather than resolved to one of its values.

#### Scenario: Unrecognized field rejected
- **WHEN** a client submits a registration request containing a field name the system does not recognize, including a differently-cased spelling of a real field
- **THEN** the system responds 400 Bad Request and does not create an account, rather than silently ignoring the field and proceeding as if it had not been sent

#### Scenario: Repeated field rejected
- **WHEN** a client submits a registration request that gives the same field twice (for example `"type":"ASSET","type":"LIABILITY"`)
- **THEN** the system responds 400 Bad Request and does not create an account, rather than keeping either value
### Requirement: The account endpoints accept no query parameters
The system SHALL respond 400 Bad Request with `Content-Type: application/problem+json`, naming every query parameter in the detail, to a request to register an account or to look one up that carries any query parameter, whatever its name or value, and SHALL register nothing for a rejected registration. A parameter that was silently ignored would be a silently dropped input, the same false success as an unrecognized field in the request body. Only the query string is examined: the fields of a form-encoded request body are not query parameters, are never reported as such, and are not read on the strength of a query string. A malformed percent-encoding in the query string SHALL likewise be a 400 with `application/problem+json`, never an internal error, and so SHALL a parameter with no name. The check precedes the account lookup. The only endpoint that accepts a query parameter is the balance read, and only `as_of` (see `account-balances`).

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

#### Scenario: The fields of a form-encoded body are not query parameters
- **WHEN** a client posts to the registration endpoint with the query string `x=1` and a form-encoded body `code=a&type=ASSET`
- **THEN** the system responds 400 Bad Request with a detail naming `x` and not `code` or `type`, and registers nothing

#### Scenario: A parameter with no name is a client error
- **WHEN** a client registers or looks up an account with the query string `=1`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail stating that a parameter has no name, not a statement about percent-encoding

### Requirement: A request body has a size limit
The system SHALL read at most 256 KiB (262,144 bytes) of a request body, and SHALL answer a body that goes past the limit with 413 Content Too Large and `Content-Type: application/problem+json` whose detail states the limit; nothing SHALL be registered for it. The limit applies to the bytes actually read, so it holds for a body that declares no length (a chunked body), and it applies whatever way the request path is spelled: percent-encoded characters in the path or `;` path parameters name the same endpoint and get the same limit. A request whose endpoint reads no body is not affected by the size of one.

#### Scenario: A body over the limit is rejected and registers nothing
- **WHEN** a client registers an account with a body of 262,145 bytes
- **THEN** the system responds 413 Content Too Large with `Content-Type: application/problem+json` and a detail stating the limit, and no account exists afterwards

#### Scenario: A body of exactly the limit is accepted
- **WHEN** a client registers an account with an otherwise valid body of exactly 262,144 bytes
- **THEN** the system responds 201 Created

#### Scenario: A body that declares no length is limited too
- **WHEN** a client registers an account with a chunked body that goes past the limit
- **THEN** the system responds 413 Content Too Large

#### Scenario: Another spelling of the path gets the same limit
- **WHEN** a client sends a body over the limit to the registration endpoint written as `/api;x=1/v1/accounts`, `/api/%761/accounts` or `/%61pi/v1/accounts`
- **THEN** the system responds 413 Content Too Large, as it does for `/api/v1/accounts`

### Requirement: Error details repeat a bounded part of a rejected value
The system SHALL NOT repeat more than 64 characters of a value it rejected in the detail of an error: a longer value is cut at 64 characters and marked with an ellipsis, so the response stays small however large the request was. A value of 64 characters or fewer is repeated whole. This covers the `code` and the `owner_ref` of a registration.

#### Scenario: A huge owner reference is not echoed in full
- **WHEN** a client registers an account with an `owner_ref` of 200,000 characters
- **THEN** the system responds 400 Bad Request whose detail contains the first 64 characters of the value followed by an ellipsis and none of the rest

#### Scenario: A huge account code is not echoed in full
- **WHEN** a client registers an account with a `code` of 200,000 characters
- **THEN** the system responds 400 Bad Request whose detail contains the first 64 characters of the value followed by an ellipsis and none of the rest

#### Scenario: A short rejected value is still named in full
- **WHEN** a client registers an account with a malformed `code` of 20 characters
- **THEN** the detail of the 400 Bad Request names the whole code
