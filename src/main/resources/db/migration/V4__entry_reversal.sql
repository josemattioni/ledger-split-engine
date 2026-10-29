-- A reversal is a new entry that exactly negates an earlier one and points back to it, so the append-only
-- history keeps both the mistake and its correction. The link lives on the reversal's own header row:
-- the original is never touched (and could not be: see V3).
--
-- Adding a nullable column rewrites no row and fires no append-only trigger. ledger_app already holds
-- table-level INSERT on journal_entries (V3), which covers the new column.
ALTER TABLE journal_entries
    ADD COLUMN reverses_entry_id BIGINT NULL,
    ADD CONSTRAINT journal_entries_reverses_entry_fk
        FOREIGN KEY (reverses_entry_id) REFERENCES journal_entries (id),
    -- At most one reversal per entry. This constraint, not a pre-check, is what enforces it: two
    -- concurrent reversals of one entry both pass any check, and ledger_app cannot take row locks. The
    -- second one waits on the first's uncommitted index entry, then fails here. NULLs (every ordinary
    -- entry) are not equal to each other, so any number of them is allowed. Only this constraint's
    -- violation is reported to the client (entry-already-reversed); the adapter matches it by name.
    ADD CONSTRAINT journal_entries_reverses_entry_uq UNIQUE (reverses_entry_id);

-- Backstop for the core invariant: the domain builds a reversal's lines by negating the original's, and anything else
-- that reaches the database fails loudly (500) at commit instead of becoming a silent bad entry. A reversal built
-- without negation would still balance, so the balance trigger (V2) cannot catch it.
--
-- LDG04: the reversal's (line_number, account_id, amount_cents) set is not the original's with each amount negated
--        (accounts are unique per entry, V3, so a set comparison is exact; EXCEPT runs both ways).
-- LDG05: the original is itself a reversal. This also rules out a self-referencing reversal and a cyclic link: both
--        give a row whose "original" is a reversal, and a cycle across statements would need an UPDATE, which V3 forbids.
--
-- Deferred to commit so the lines can follow the header. A header with no lines reports LDG03 first.
CREATE FUNCTION check_reversal_matches_original() RETURNS trigger AS $$
DECLARE
    original_is_reversal BOOLEAN;
BEGIN
    SELECT reverses_entry_id IS NOT NULL INTO original_is_reversal
    FROM journal_entries
    WHERE id = NEW.reverses_entry_id;

    IF original_is_reversal THEN
        RAISE EXCEPTION 'journal entry % reverses entry %, which is itself a reversal', NEW.id, NEW.reverses_entry_id
            USING ERRCODE = 'LDG05';
    END IF;

    IF EXISTS (
        (SELECT line_number, account_id, amount_cents
         FROM entry_lines WHERE journal_entry_id = NEW.id
         EXCEPT
         SELECT line_number, account_id, -amount_cents
         FROM entry_lines WHERE journal_entry_id = NEW.reverses_entry_id)
        UNION ALL
        (SELECT line_number, account_id, -amount_cents
         FROM entry_lines WHERE journal_entry_id = NEW.reverses_entry_id
         EXCEPT
         SELECT line_number, account_id, amount_cents
         FROM entry_lines WHERE journal_entry_id = NEW.id)
    ) THEN
        RAISE EXCEPTION 'journal entry % is not the exact negation of entry %', NEW.id, NEW.reverses_entry_id
            USING ERRCODE = 'LDG04';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entries_reversal_matches
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.reverses_entry_id IS NOT NULL)
    EXECUTE FUNCTION check_reversal_matches_original();
