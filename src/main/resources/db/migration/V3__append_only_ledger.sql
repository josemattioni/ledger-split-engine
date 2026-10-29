-- The application connects as ledger_app, a role created by docker/initdb/01-create-ledger-app-role.sql
-- (roles are cluster-level, so Flyway only grants privileges). It can read and insert; it has no
-- UPDATE, DELETE or TRUNCATE on any table, accounts included.
-- Identity columns need no separate sequence grant: Postgres does not check USAGE on an identity
-- column's implicit sequence when an INSERT generates the value.
GRANT SELECT, INSERT ON accounts, journal_entries, entry_lines TO ledger_app;

-- Tables created by later migrations (run by this same owner role) get the same restricted access.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES TO ledger_app;

-- Second layer: even a role that gets past the grants (the table owner, for one) cannot change or
-- remove ledger rows. The owner can still DISABLE or DROP these triggers; that role is for
-- migrations only (see README, "Append-only guarantees and their limits").
-- accounts is deliberately not covered here; ledger_app already cannot UPDATE it by grants.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the ledger is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'LDG02';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- TRUNCATE does not fire row-level triggers, so it needs its own statement-level trigger.
CREATE TRIGGER trg_journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER trg_entry_lines_append_only
    BEFORE UPDATE OR DELETE ON entry_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER trg_entry_lines_no_truncate
    BEFORE TRUNCATE ON entry_lines
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_mutation();

-- Degenerate-entry backstops. The domain rejects these shapes first (422); if anything reaches the
-- database it is a bug, and these turn it into a loud failure (500) instead of a silent bad entry.
-- The constraints are named so the adapter and tests can tell them apart from the idempotency-key
-- constraint: only journal_entries_idempotency_key_uq may ever be reported as a 409.
ALTER TABLE entry_lines
    ADD CONSTRAINT entry_lines_amount_nonzero_ck CHECK (amount_cents <> 0),
    ADD CONSTRAINT entry_lines_entry_account_uq UNIQUE (journal_entry_id, account_id);

-- A header with no lines never fires trg_entry_lines_balance (that trigger is on entry_lines), so the
-- two-line minimum is checked on the header itself, deferred to commit so the lines can be inserted
-- after it in the same transaction. Together with the CHECK above and the balance trigger this makes
-- a one-line entry impossible: its only line is either zero or unbalanced.
CREATE FUNCTION check_entry_has_enough_lines() RETURNS trigger AS $$
DECLARE
    line_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO line_count
    FROM entry_lines
    WHERE journal_entry_id = NEW.id;

    IF line_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % line(s); at least 2 are required', NEW.id, line_count
            USING ERRCODE = 'LDG03';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entries_min_lines
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entry_has_enough_lines();
