## MODIFIED Requirements

### Requirement: Account type validation
The system SHALL accept only one of the fixed accounting classifications `ASSET`, `LIABILITY`, `REVENUE`, or `EXPENSE` as an account's `type`, written as that name, and SHALL reject any other value. A position in the list of classifications is not a name, whether it arrives as a JSON number or as a string holding a number.

#### Scenario: Invalid type rejected
- **WHEN** a client submits a registration request with a `type` outside the fixed set of accounting classifications
- **THEN** the system responds 400 Bad Request with an error message describing the allowed values, and does not create an account

#### Scenario: Type given as a quoted index rejected
- **WHEN** a client submits a registration request whose `type` is a string holding a number, such as `"0"` or `"3"`
- **THEN** the system responds 400 Bad Request and does not create an account, rather than treating the number as a position in the list of classifications

### Requirement: Request field names are exact and snake_case
The system SHALL accept request field names in snake_case (matching the database column names, e.g. `owner_ref`), and SHALL reject a request containing a field name it does not recognize rather than silently ignoring it. A differently-cased spelling of a known field (e.g. `ownerRef` instead of `owner_ref`) is an unrecognized field, not an accepted alias. A field given more than once in the same request SHALL be rejected rather than resolved to one of its values.

#### Scenario: Unrecognized field rejected
- **WHEN** a client submits a registration request containing a field name the system does not recognize, including a differently-cased spelling of a real field
- **THEN** the system responds 400 Bad Request and does not create an account, rather than silently ignoring the field and proceeding as if it had not been sent

#### Scenario: Repeated field rejected
- **WHEN** a client submits a registration request that gives the same field twice (for example `"type":"ASSET","type":"LIABILITY"`)
- **THEN** the system responds 400 Bad Request and does not create an account, rather than keeping either value
