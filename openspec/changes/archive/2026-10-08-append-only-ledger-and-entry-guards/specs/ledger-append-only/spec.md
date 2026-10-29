## Purpose

Guarantees that ledger rows, once written, can never be edited or removed: the application's database identity can only read and insert, and database-level triggers refuse mutation even from the migration owner. A correction to the ledger is always a new entry, never a change to an existing row.

## ADDED Requirements

### Requirement: The application's database identity can only read and insert
The system SHALL perform all application database access under a database identity that holds `SELECT` and `INSERT` privileges on every ledger table (accounts, journal entries and entry lines) and no `UPDATE`, `DELETE` or `TRUNCATE` privilege on any of them. An attempt under that identity to update, delete or truncate any ledger table SHALL be refused as a permission denial.

#### Scenario: Update refused for the application identity
- **WHEN** a statement run under the application's database identity attempts to update a row of accounts, journal entries or entry lines
- **THEN** the database refuses it with a permission-denied error and the row is unchanged

#### Scenario: Delete refused for the application identity
- **WHEN** a statement run under the application's database identity attempts to delete a row of accounts, journal entries or entry lines
- **THEN** the database refuses it with a permission-denied error and the row remains

#### Scenario: Truncate refused for the application identity
- **WHEN** a statement run under the application's database identity attempts to truncate accounts, journal entries or entry lines
- **THEN** the database refuses it with a permission-denied error and no rows are removed

#### Scenario: Posting and reading still work under the application identity
- **WHEN** a client registers accounts, posts a valid journal entry and reads a balance through the API
- **THEN** every request succeeds, including the generation of internal row identifiers, without the application identity holding any privilege beyond reading and inserting

### Requirement: Tables created by later migrations get the same restricted access
The system SHALL grant the application's database identity the same read-and-insert-only privileges on any table created by a later schema migration, without that migration having to grant them explicitly, so a new ledger table can never be created with broader application access by omission.

#### Scenario: A newly created table is read-and-insert only for the application identity
- **WHEN** the migration owner creates a new table after the initial grants were applied
- **THEN** the application's database identity can read and insert into it but holds no update, delete or truncate privilege on it

### Requirement: Schema changes run under a separate owner identity
The system SHALL apply schema migrations under a database identity distinct from the application's, and the application's identity SHALL NOT be able to create, alter or drop database objects.

#### Scenario: Migrations are applied by the owner identity
- **WHEN** the application starts against an empty database and applies its migrations
- **THEN** the migration history records every migration as applied by the owner identity, not the application identity

#### Scenario: Application identity cannot change the schema
- **WHEN** a statement run under the application's database identity attempts to create, alter or drop a table
- **THEN** the database refuses it with a permission-denied error

### Requirement: Ledger rows cannot be changed or removed even by the table owner
The system SHALL refuse, at the database level, any update or delete of a journal entry or entry line row and any truncation of the journal entries or entry lines tables, regardless of the privileges of the identity attempting it, with an error stating that the ledger is append-only. This guard is a second layer behind the application identity's missing privileges; the table owner retains the ability to disable it, which is why the owner identity is reserved for migrations.

#### Scenario: Owner's update of a journal entry row refused
- **WHEN** the table owner attempts to update an existing journal entry row or an existing entry line row
- **THEN** the database refuses it with an error stating that the ledger is append-only, and the row is unchanged

#### Scenario: Owner's delete of a journal entry row refused
- **WHEN** the table owner attempts to delete an existing journal entry row or an existing entry line row
- **THEN** the database refuses it with an error stating that the ledger is append-only, and the row remains

#### Scenario: Owner's truncate refused
- **WHEN** the table owner attempts to truncate the journal entries table or the entry lines table
- **THEN** the database refuses it with an error stating that the ledger is append-only, and no rows are removed
