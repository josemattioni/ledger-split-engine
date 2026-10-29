## MODIFIED Requirements

### Requirement: The application's database identity can only read and insert
The system SHALL perform all application database access under a database identity that holds `SELECT` on every ledger table (accounts, journal entries and entry lines), `INSERT` only on the columns a client supplies (see "Server-assigned columns can only come from their defaults"), and no `UPDATE`, `DELETE` or `TRUNCATE` privilege on any of them. An attempt under that identity to update, delete or truncate any ledger table SHALL be refused as a permission denial.

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
- **THEN** every request succeeds, including the generation of internal row identifiers, without the application identity holding any privilege beyond reading, and inserting the columns a client supplies

### Requirement: Ledger rows cannot be changed or removed even by the table owner
The system SHALL refuse, at the database level, any update or delete of an account, a journal entry or an entry line row and any truncation of the accounts, journal entries or entry lines tables, regardless of the privileges of the identity attempting it, with an error stating that the ledger is append-only. This guard is a second layer behind the application identity's missing privileges; the table owner retains the ability to disable it, which is why the owner identity is reserved for migrations.

#### Scenario: Owner's update of a journal entry row refused
- **WHEN** the table owner attempts to update an existing journal entry row or an existing entry line row
- **THEN** the database refuses it with an error stating that the ledger is append-only, and the row is unchanged

#### Scenario: Owner's delete of a journal entry row refused
- **WHEN** the table owner attempts to delete an existing journal entry row or an existing entry line row
- **THEN** the database refuses it with an error stating that the ledger is append-only, and the row remains

#### Scenario: Owner's truncate refused
- **WHEN** the table owner attempts to truncate the journal entries table or the entry lines table
- **THEN** the database refuses it with an error stating that the ledger is append-only, and no rows are removed

#### Scenario: Owner's update of an account refused
- **WHEN** the table owner attempts to update an existing account, whether or not it has entry lines
- **THEN** the database refuses it with an error stating that the ledger is append-only, and the account is unchanged

#### Scenario: Owner's delete of an account refused
- **WHEN** the table owner attempts to delete an existing account, whether or not it has entry lines
- **THEN** the database refuses it with an error stating that the ledger is append-only, and the account remains

#### Scenario: Owner's truncate of the accounts table refused
- **WHEN** the table owner attempts to truncate the accounts table, whether together with the tables that reference it or by cascading to them
- **THEN** the database refuses it with an error stating that the ledger is append-only, and no rows are removed

#### Scenario: Owner's truncate of the accounts table alone is refused while entry lines reference it
- **WHEN** the table owner attempts to truncate the accounts table by itself while entry lines reference accounts
- **THEN** the database refuses it, because a referenced table cannot be truncated alone, and no rows are removed

## ADDED Requirements

### Requirement: Server-assigned columns can only come from their defaults
The system SHALL ensure that the application's database identity cannot supply a value for any column the server assigns: an account's `id`, `currency` and `created_at`, a journal entry's `id`, `entry_id` and `created_at`, and an entry line's `id`. Those values come only from the column defaults, so no row can be written with an invented identifier, currency or timestamp, including by explicitly overriding a generated identity. An attempt SHALL be refused as a permission denial and SHALL write nothing. The application identity's insert privilege SHALL be granted on precisely the columns a client supplies (an account's `code`, `owner_ref` and `type`; a journal entry's `idempotency_key` and `reverses_entry_id`; an entry line's `journal_entry_id`, `line_number`, `account_id` and `amount_cents`) and on no other column.

#### Scenario: Supplying a server-assigned column is refused
- **WHEN** a statement run under the application's database identity inserts a row that names an account's `currency` or `created_at`, a journal entry's `entry_id` or `created_at`, or any other column the server assigns
- **THEN** the database refuses it with a permission-denied error and no row is written

#### Scenario: Overriding a generated identity is refused
- **WHEN** a statement run under the application's database identity inserts a row of accounts, journal entries or entry lines naming its `id` and overriding the generated identity
- **THEN** the database refuses it with a permission-denied error and no row is written

#### Scenario: The insert privileges are exactly the client-supplied columns
- **WHEN** the application identity's insert privileges are inspected column by column on accounts, journal entries and entry lines
- **THEN** it holds the privilege on exactly the columns listed above and on no others

#### Scenario: A server-assigned value comes from the database
- **WHEN** a client registers an account and posts an entry through the API
- **THEN** the stored identifiers, currency and timestamps are the database's own defaults

### Requirement: Tables created by later migrations are read-only for the application until granted
The system SHALL give the application's database identity `SELECT` on any table created by a later schema migration without that migration having to grant it, and SHALL NOT give it `INSERT`, `UPDATE`, `DELETE` or `TRUNCATE` on that table until a migration grants `INSERT` explicitly, on named columns, so that a new table can never be writable by the application by omission.

#### Scenario: A newly created table is readable but not writable for the application identity
- **WHEN** the migration owner creates a new table after the initial grants were applied
- **THEN** the application's database identity can read it and holds no insert, update, delete or truncate privilege on it

#### Scenario: Writing a new table needs an explicit column-level grant
- **WHEN** a later migration grants the application identity insert on named columns of a new table
- **THEN** the application identity can insert those columns and no others

## REMOVED Requirements

### Requirement: Tables created by later migrations get the same restricted access
**Reason**: It promised the application identity `INSERT` on every later table by default. That is the opposite of the new rule, under which a table is writable by the application only through an explicit column-level grant, so a server-assigned column can never be insertable by omission.
**Migration**: Replaced by "Tables created by later migrations are read-only for the application until granted" and "Server-assigned columns can only come from their defaults". A migration that creates a table the application writes to grants `INSERT` on the client-supplied columns in the same migration.
