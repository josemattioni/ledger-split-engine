## ADDED Requirements

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
