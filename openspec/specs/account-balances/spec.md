# account-balances Specification

## Purpose
Lets a client read an account's current balance, derived on demand from its posted journal entry lines rather than stored anywhere, so the balance can never drift out of sync with the ledger. A balance can also be read as of a past instant, answered only once that instant has settled so that the same question always gets the same answer.

## Requirements

### Requirement: Read an account's derived balance
The system SHALL allow a client to retrieve a registered account's balance by its `code`, computed as the sum of `amount_cents` across all of that account's journal entry lines, with no normalization by the account's accounting type. Without an `as_of` parameter the sum covers every line posted so far (the current balance); with one, see "A balance can be read as of a past instant". Every balance response includes `as_of`, which is `null` for a current balance.

#### Scenario: Balance reflects posted entries
- **WHEN** a client requests the balance of an account that has posted journal entry lines
- **THEN** the system responds 200 OK with a body of exactly `account_code` (the requested account's code), `currency`, `balance_cents` equal to the signed sum of that account's line amounts, and `as_of` equal to `null`

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
### Requirement: A balance can be read as of a past instant
The system SHALL allow a client to read an account's balance as of an instant with `GET /api/v1/accounts/{code}/balance?as_of=<instant>`: the signed sum of `amount_cents` over the lines of every entry recorded (`created_at`) at or before that instant, inclusive, at microsecond precision. The response has the same body as a current balance, with `as_of` holding the requested instant expressed in UTC. Entries are ordered by when they were recorded, never by the order of their internal identifiers. A balance as of a settled instant (see "A balance is answered only for settled instants") SHALL give the same answer every time it is asked, whatever is posted afterwards.

#### Scenario: An instant between two entries sees only the earlier one
- **WHEN** an account has lines from entries recorded at times t1 < t2 < t3, and a client reads its balance as of a settled instant between t1 and t2
- **THEN** the system responds 200 OK with `balance_cents` equal to the sum of the lines recorded at or before t1 only

#### Scenario: The instant itself is included
- **WHEN** a client reads an account's balance as of exactly the recorded time of one of its entries, a settled instant
- **THEN** that entry's line is included in `balance_cents`

#### Scenario: An account that did not exist yet has a zero balance
- **WHEN** a client reads the balance of a registered account as of a settled instant earlier than the account was registered
- **THEN** the system responds 200 OK with `balance_cents` equal to zero, not 404 Not Found

#### Scenario: A reversal appears only from when it was recorded
- **WHEN** an entry was recorded at t1 and its reversal at t2 > t1, and a client reads an affected account's balance as of a settled instant between t1 and t2, and again as of a settled instant after t2
- **THEN** the first answer shows the original entry's effect and the second shows that effect cancelled

#### Scenario: The instant is echoed in UTC
- **WHEN** a client reads a balance with an `as_of` written with an offset such as `-03:00`
- **THEN** the body's `as_of` is the same instant expressed in UTC

#### Scenario: A later posting does not change an earlier answer
- **WHEN** a client reads an account's balance as of a settled instant, then entries are posted to that account, then the client reads it as of the same instant again
- **THEN** both answers are identical

### Requirement: The as_of parameter is a strict RFC 3339 instant
The system SHALL accept `as_of` only as an RFC 3339 date-time with an explicit UTC offset: `YYYY-MM-DDThh:mm:ss`, an optional fraction of 1 to 6 digits, and an offset of `Z` or `±hh:mm` between -18:00 and +18:00, with an uppercase `T` and `Z`. Seconds are required. The system SHALL respond 400 Bad Request with `application/problem+json`, and SHALL read nothing, to an `as_of` that does not match (including an impossible date or time), an empty `as_of`, an `as_of` given more than once, and a query string whose percent-encoding is malformed. A client error SHALL never be reported as an internal error.

#### Scenario: Equivalent spellings of one instant are answered identically
- **WHEN** a client reads a balance as of `2026-10-21T15:00:00Z`, `2026-10-21T15:00:00+00:00`, `2026-10-21T12:00:00-03:00` (the offset sent as `-03:00` or as `%2D03:00`) and `2026-10-21T15:00:00.000000Z`
- **THEN** the system answers all of them identically, with the same `balance_cents` and the same `as_of`

#### Scenario: A fraction of up to six digits is accepted
- **WHEN** a client reads a balance with an `as_of` carrying 0, 1, 3 or 6 fractional digits
- **THEN** the system accepts it and the answer reflects the instant to the microsecond

#### Scenario: A value that is not an RFC 3339 instant with an offset is rejected
- **WHEN** a client sends an `as_of` with no offset, a date only, no seconds, seven fractional digits, an epoch number, a lowercase `t` or `z`, or a month or hour that does not exist
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail naming `as_of`

#### Scenario: An empty as_of is rejected
- **WHEN** a client sends `as_of=` with no value
- **THEN** the system responds 400 Bad Request, not the current balance

#### Scenario: A repeated as_of is rejected
- **WHEN** a client sends `as_of` twice
- **THEN** the system responds 400 Bad Request, whatever the two values are

#### Scenario: An unencoded plus sign is rejected
- **WHEN** a client sends a positive offset with a literal `+` in the query string, which a query string decodes as a space
- **THEN** the system responds 400 Bad Request; the same offset written as `%2B` is accepted

#### Scenario: A malformed percent-encoding is a client error
- **WHEN** a client sends an `as_of` whose percent-encoding is malformed, such as `%zz` or an incomplete `%`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail stating that the query string is not valid percent-encoded UTF-8, not 500 Internal Server Error; the detail does not repeat what the client sent

### Requirement: Query parameters other than as_of are rejected
The system SHALL accept `as_of` as the only query parameter of a balance request. It SHALL respond 400 Bad Request with `application/problem+json`, naming every parameter it does not accept, and SHALL read nothing, to a request that carries any other query parameter, whatever its value and whether or not a valid `as_of` is also present. Names are matched exactly: `asOf` and `AS_OF` are unknown parameters, not spellings of `as_of`. A parameter that was silently ignored would answer a request for a past balance with the current one and report success. No other endpoint accepts a query parameter at all (see `account-registration`, `journal-entries` and `journal-entry-reversal`).

#### Scenario: A misspelled as_of is rejected, not ignored
- **WHEN** a client sends `asOf` with a valid instant
- **THEN** the system responds 400 Bad Request with a detail naming `asOf`, not 200 OK with the current balance

#### Scenario: A differently cased as_of is rejected
- **WHEN** a client sends `AS_OF` with a valid instant
- **THEN** the system responds 400 Bad Request with a detail naming `AS_OF`

#### Scenario: An extra parameter next to a valid as_of is rejected
- **WHEN** a client sends a valid `as_of` and also `x=1`
- **THEN** the system responds 400 Bad Request with a detail naming `x`, and no balance is read

#### Scenario: An unknown parameter with a malformed percent-encoding is a client error
- **WHEN** a client sends `x=%zz`
- **THEN** the system responds 400 Bad Request with `Content-Type: application/problem+json` and a detail stating that the query string is not valid percent-encoded UTF-8, not 500 Internal Server Error

### Requirement: A balance is answered only for settled instants
The system SHALL answer an as-of balance only when `as_of` is not later than the database's current time minus 60 seconds (the settle window), and SHALL otherwise respond 422 Unprocessable Entity with `Content-Type: application/problem+json`, the problem `type` `urn:ledger-split-engine:problem:as-of-not-settled`, and a detail that states the window. This includes any instant in the future. Both instants come from the database clock, not the application's. The window exists because an entry is recorded at the start of its transaction but becomes visible only when that transaction commits: within the window an answer could still change. A request without `as_of` is not subject to the window.

#### Scenario: An instant older than the window is answered
- **WHEN** a client reads a balance as of an instant 61 seconds before the database's current time
- **THEN** the system responds 200 OK with the sum of the lines recorded at or before it

#### Scenario: An instant exactly one window old is answered
- **WHEN** a client reads a balance as of exactly 60 seconds before the database's current time
- **THEN** the system responds 200 OK

#### Scenario: An instant inside the window is refused
- **WHEN** a client reads a balance as of an instant 59 seconds before the database's current time
- **THEN** the system responds 422 Unprocessable Entity with the type `urn:ledger-split-engine:problem:as-of-not-settled` and a detail stating the 60-second window

#### Scenario: A future instant is refused
- **WHEN** a client reads a balance as of an instant later than the database's current time
- **THEN** the system responds 422 Unprocessable Entity with the type `urn:ledger-split-engine:problem:as-of-not-settled`

#### Scenario: A new posting is in the current balance but not in a recent as-of
- **WHEN** a client posts an entry and then reads the account's current balance, and its balance as of 61 seconds before the database's current time
- **THEN** the current balance includes the entry and the as-of balance does not

### Requirement: Balance errors are chosen in a fixed order
The system SHALL evaluate the errors of a balance request in this order and report the first that applies: a malformed `as_of`, a query parameter other than `as_of`, or a malformed query string (all 400), an unknown account (404), an `as_of` that is not settled (422).

#### Scenario: A malformed as_of is reported before an unknown account
- **WHEN** a client reads the balance of an unregistered `code` with an `as_of` that is not an RFC 3339 instant
- **THEN** the system responds 400 Bad Request

#### Scenario: An unknown parameter is reported before an unknown account
- **WHEN** a client reads the balance of an unregistered `code` with a query parameter other than `as_of`
- **THEN** the system responds 400 Bad Request, not 404

#### Scenario: An unknown account is reported before an unsettled as_of
- **WHEN** a client reads the balance of an unregistered `code` with a well-formed `as_of` that is inside the settle window
- **THEN** the system responds 404 Not Found, not 422

### Requirement: A settled answer cannot change after the fact
The system SHALL give its application database identity a default bound of 30 seconds on every transaction, so that an entry recorded at or before the settled horizon has by then either committed or been abandoned. A transaction that exceeds the bound SHALL end without committing and leave nothing behind. The bound is a default of the identity, not a restriction on it: a session of that identity can change it for itself, and the startup check below reads it once. The guarantee that a settled answer never changes therefore holds as long as the application keeps the default, which it does (it never changes the setting); it is not a defence against a client that holds the application's database credentials. Prepared transactions SHALL be disabled in the database, because a prepared transaction leaves its session and can be committed much later while keeping the timestamp of its start, which no per-session bound can limit. The system SHALL refuse to start unless that bound is set on its database identity and is greater than zero and at most half the settle window, and prepared transactions are disabled, and SHALL say in the failure which of these is wrong.

#### Scenario: A transaction that outlives its bound never commits
- **WHEN** a transaction of the application's database identity stays open longer than its bound and then attempts to commit
- **THEN** its connection is terminated and none of the rows it wrote exist

#### Scenario: Startup is refused without a bound
- **WHEN** the application starts against a database in which its identity has no transaction bound
- **THEN** it fails to start with a message stating that the bound is missing

#### Scenario: Startup is refused with a bound that is too long
- **WHEN** the application starts against a database in which its identity's transaction bound is more than 30 seconds
- **THEN** it fails to start with a message stating the largest bound it accepts

#### Scenario: Startup is refused when prepared transactions are enabled
- **WHEN** the application starts against a database in which prepared transactions are enabled, whatever its identity's transaction bound
- **THEN** it fails to start with a message naming the prepared-transactions setting

#### Scenario: Startup succeeds with the bound in place and prepared transactions disabled
- **WHEN** the application starts against a database in which its identity's transaction bound is 30 seconds and prepared transactions are disabled
- **THEN** it starts and serves requests

#### Scenario: A session can change its own bound, which is outside the guarantee
- **WHEN** a session of the application's database identity sets its own transaction bound to zero
- **THEN** the database accepts it for that session, and the guarantee of a settled answer is not claimed for a session that did so
