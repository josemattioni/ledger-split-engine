## Purpose

Lets a client read an account's current balance, derived on demand from its posted journal entry lines rather than stored anywhere, so the balance can never drift out of sync with the ledger.

## ADDED Requirements

### Requirement: Read an account's derived balance
The system SHALL allow a client to retrieve a registered account's balance by its `code`, computed as the sum of `amount_cents` across all of that account's journal entry lines, with no normalization by the account's accounting type.

#### Scenario: Balance reflects posted entries
- **WHEN** a client requests the balance of an account that has posted journal entry lines
- **THEN** the system responds 200 OK with a body of exactly `account_code` (the requested account's code), `currency`, and `balance_cents` equal to the signed sum of that account's line amounts

#### Scenario: Balance is zero with no lines
- **WHEN** a client requests the balance of a registered account that has no journal entry lines
- **THEN** the system responds 200 OK with `balance_cents` equal to zero

#### Scenario: Credit-normal account can show a negative balance
- **WHEN** an account whose type is normally a credit balance (for example a liability) has lines whose signed sum is negative
- **THEN** the system reports that negative value as-is, without flipping its sign to a conventional positive presentation

### Requirement: Balance lookup requires a registered account
The system SHALL reject a balance request for a `code` that does not match any registered account.

#### Scenario: Unknown account balance lookup
- **WHEN** a client requests the balance of a `code` that has not been registered
- **THEN** the system responds 404 Not Found with `Content-Type: application/problem+json` and a detail describing that no account exists for that code

### Requirement: Balance errors are reported as problem+json
The system SHALL report every error response for a balance lookup as `application/problem+json`, consistent with every other endpoint in the system.

#### Scenario: Not-found error carries a description
- **WHEN** a balance lookup is rejected because the account does not exist
- **THEN** the response has `Content-Type: application/problem+json`, not a bare status code with no body
