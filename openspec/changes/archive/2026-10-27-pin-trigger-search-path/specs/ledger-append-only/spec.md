## ADDED Requirements

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
