# ledger-append-only Specification

## Purpose
Guarantees that ledger rows (accounts included), once written, can never be edited or removed: the application's database identity can only read and insert, and only the columns a client supplies, so a server-assigned value such as a timestamp cannot be invented; database-level triggers refuse mutation even from the migration owner, and an entry's lines are fixed when it is recorded (a line cannot be added to an entry committed earlier), and the database checks behind these guarantees read the ledger tables themselves, so no session can redirect them. A correction to the ledger is always a new entry, never a change to an existing row.

## Requirements

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

### Requirement: Schema changes run under a separate owner identity
The system SHALL apply schema migrations under a database identity distinct from the application's, and the application's identity SHALL NOT be able to create, alter or drop database objects.

#### Scenario: Migrations are applied by the owner identity
- **WHEN** the application starts against an empty database and applies its migrations
- **THEN** the migration history records every migration as applied by the owner identity, not the application identity

#### Scenario: Application identity cannot change the schema
- **WHEN** a statement run under the application's database identity attempts to create, alter or drop a table
- **THEN** the database refuses it with a permission-denied error

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
### Requirement: A line can only be added in the transaction that inserts its entry
The system SHALL refuse, in the database, to insert an entry line unless the journal entry it belongs to was inserted by the same transaction, and SHALL refuse it for the application's database identity and for the table owner alike, with an error code of its own (`LDG06`) and without writing the line. An entry's lines are therefore fixed at the moment the entry is recorded: no line can be added to an entry committed earlier, whether or not the added lines would keep the entry balanced. This protects what the as-of balance relies on (an entry's effect on a balance dates from its `created_at`) and the reversal rule (a reversal is the exact negation of the original's lines). Posting an entry and reversing one insert the entry and its lines together and are unaffected.

#### Scenario: A balanced pair of lines cannot be added to an entry committed earlier
- **WHEN** a transaction under the application's database identity inserts two lines that sum to zero into an entry that an earlier transaction committed
- **THEN** the database refuses the insert with the error code `LDG06` and the entry's lines and every balance are unchanged

#### Scenario: The table owner is bound too
- **WHEN** a transaction under the table owner inserts a line into an entry that an earlier transaction committed
- **THEN** the database refuses the insert with the error code `LDG06`

#### Scenario: Lines inserted with their entry commit
- **WHEN** one transaction inserts an entry and its lines and commits
- **THEN** the entry and its lines exist, whether it was posted by a client or created by a reversal

#### Scenario: A line for an entry that does not exist is still a foreign-key violation
- **WHEN** a transaction inserts a line whose `journal_entry_id` matches no entry
- **THEN** the database refuses it with the foreign-key violation it always gave, not with `LDG06`
### Requirement: The database checks cannot be redirected by the session
The system SHALL make every database check on the ledger (the entry balance, the minimum of two lines, the two reversal checks, and the rule that a line is added only in the transaction that inserted its entry) read the ledger tables themselves, whatever the session's search path is. No session, whether of the application's database identity or of any other, SHALL be able to make a check pass by creating an object with a ledger table's name in a schema that is searched earlier, such as a temporary table. Each check SHALL fix the schemas it searches, listing the schema of the ledger tables before the temporary one, and a check added later SHALL do the same. This does not make the checks a defense against the table owner, who can still disable or drop them (see "Ledger rows cannot be changed or removed even by the table owner").

#### Scenario: Shadowing the journal entries table does not allow a line to be added to an earlier entry
- **WHEN** a transaction under the application's database identity creates a temporary table with the name of the journal entries table, puts the identifier of an entry that an earlier transaction committed into it, and inserts two lines that sum to zero into that entry
- **THEN** the database refuses the insert with the error code `LDG06` and the entry's lines and every balance are unchanged

#### Scenario: Shadowing the entry lines table does not let an unbalanced entry commit
- **WHEN** a transaction under the application's database identity inserts an entry whose lines do not sum to zero, then creates a temporary table with the name of the entry lines table holding two lines for that entry that do sum to zero, and commits
- **THEN** the database refuses the commit with the error code `LDG01` and nothing is written

#### Scenario: Shadowing the entry lines table does not let an entry with fewer than two lines commit
- **WHEN** a transaction under the application's database identity inserts an entry with no lines, then creates a temporary table with the name of the entry lines table holding two lines for that entry, and commits
- **THEN** the database refuses the commit with the error code `LDG03` and nothing is written

#### Scenario: Shadowing does not let a reversal that is not the exact negation commit
- **WHEN** a transaction under the application's database identity inserts a reversal whose lines are not the original's with each amount negated, then creates temporary tables with the names of the ledger tables that present the exact negation, and commits
- **THEN** the database refuses the commit with the error code `LDG04` and nothing is written

#### Scenario: Shadowing does not let a reversal of a reversal commit
- **WHEN** a transaction under the application's database identity inserts an entry that reverses an entry which is itself a reversal, after creating a temporary table with the name of the journal entries table that presents the original as an ordinary entry, and commits
- **THEN** the database refuses the commit with the error code `LDG05` and nothing is written

#### Scenario: A session with an empty search path loads a valid entry
- **WHEN** a session whose search path is empty, as a restore tool's is, inserts a valid entry and its lines in one transaction and commits
- **THEN** the entry and its lines exist, and the same checks have applied as for any other session

#### Scenario: Every check fixes the schemas it searches
- **WHEN** the database's checking routines for the ledger are inspected
- **THEN** each one fixes its search path to the schema of the ledger tables followed by the temporary schema
