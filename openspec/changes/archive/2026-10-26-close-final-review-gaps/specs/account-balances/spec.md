## MODIFIED Requirements

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
