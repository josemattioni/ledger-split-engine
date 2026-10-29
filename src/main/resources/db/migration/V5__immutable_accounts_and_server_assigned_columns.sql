-- Two guarantees. Accounts can no longer be changed, even by the owner. And the application role can no
-- longer supply a value the server assigns (id, entry_id, created_at, currency): an inserted created_at
-- could rewrite history, which the as-of balance must be able to rule out.

-- 1. Accounts are part of the append-only ledger: the same mechanism V3 gave journal_entries and entry_lines.
-- ledger_app already has no UPDATE, DELETE or TRUNCATE on accounts (V3 grants); this stops the owner too. The
-- owner can still DISABLE or DROP these triggers, which is why that role is for migrations only (see README,
-- "Append-only guarantees and their limits"). Nothing here allows renaming an account: a stored entry reloads
-- with the account's current code, so a rename would turn a retry of an old request into a key-reuse error.
-- A bare TRUNCATE accounts never reaches the trigger: Postgres refuses it first because entry_lines
-- references accounts (0A000). TRUNCATE ... CASCADE, or listing
-- entry_lines as well, does reach it.
CREATE TRIGGER trg_accounts_append_only
    BEFORE UPDATE OR DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER trg_accounts_no_truncate
    BEFORE TRUNCATE ON accounts
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_mutation();

-- 2. ledger_app may insert only the columns a client supplies. V3 granted INSERT on the whole table, which let
-- the application name id (with OVERRIDING SYSTEM VALUE), entry_id, created_at or currency. A column-level
-- grant leaves those to their defaults, and an attempt is refused with 42501 and writes nothing.
-- The REVOKE must come first: a table-level REVOKE also removes column-level grants, so granting the
-- columns before it would leave ledger_app unable to insert anything.
REVOKE INSERT ON accounts, journal_entries, entry_lines FROM ledger_app;

GRANT INSERT (code, owner_ref, type) ON accounts TO ledger_app;
GRANT INSERT (idempotency_key, reverses_entry_id) ON journal_entries TO ledger_app;
GRANT INSERT (journal_entry_id, line_number, account_id, amount_cents) ON entry_lines TO ledger_app;

-- 3. Tables created by later migrations: V3 made INSERT automatic for ledger_app, so a new table was writable by
-- omission. From here a new table is readable automatically (SELECT is still a default privilege) and
-- writable only through an INSERT grant on named columns, in the migration that creates the table. Default
-- privileges apply to objects created later by the role running this statement, which is the owner Flyway
-- runs as; existing tables are covered by section 2.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE INSERT ON TABLES FROM ledger_app;
