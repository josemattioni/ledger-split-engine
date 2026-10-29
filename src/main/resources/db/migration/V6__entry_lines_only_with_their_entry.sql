-- LDG06: a line can only be added in the transaction that inserted its entry.
--
-- Why. ledger_app's INSERT on entry_lines covers any journal_entry_id, and the balance check (LDG01) only sums, so two
-- lines that cancel out (+5000 / -5000) could be added to an entry committed days ago and pass every check, changing
-- each balance as of any later instant (the guarantee V5 protects) and leaving the entry's reversal no longer its exact
-- negation. The two-line and reversal triggers fire only when a header is inserted.
--
-- How. A line is refused unless its header's xmin is the current transaction's xid; journal_entries is never updated
-- (LDG02), so xmin is a reliable "who inserted this header". The cast to xid keeps 32 bits and freezing keeps the raw
-- xmin (PostgreSQL 9.4 and later), so the check could be fooled only after 2^32 transactions. Every role is bound, the
-- owner included, who can still DISABLE or DROP the trigger.
--
-- Limits (a header inserted in a savepoint is refused; restores) and the restore recipes: docs/ledger-guarantees.md.
CREATE FUNCTION check_line_added_with_its_entry() RETURNS trigger AS $$
DECLARE
    header_xmin xid;
BEGIN
    SELECT xmin INTO header_xmin FROM journal_entries WHERE id = NEW.journal_entry_id;

    IF FOUND AND header_xmin <> pg_current_xact_id()::xid THEN
        RAISE EXCEPTION 'entry_lines: a line can only be added in the transaction that inserted its journal entry (journal_entry_id %)',
            NEW.journal_entry_id
            USING ERRCODE = 'LDG06';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entry_lines_with_their_entry
    BEFORE INSERT ON entry_lines
    FOR EACH ROW EXECUTE FUNCTION check_line_added_with_its_entry();
