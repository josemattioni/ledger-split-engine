CREATE TABLE journal_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL
        CHECK (idempotency_key ~ '^[!-~]{1,255}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT journal_entries_idempotency_key_uq UNIQUE (idempotency_key)
);

CREATE TABLE entry_lines (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entries (id),
    line_number INT NOT NULL CHECK (line_number >= 1),
    account_id BIGINT NOT NULL REFERENCES accounts (id),
    amount_cents BIGINT NOT NULL,
    UNIQUE (journal_entry_id, line_number)
);

CREATE INDEX entry_lines_account_id_idx ON entry_lines (account_id);

CREATE FUNCTION check_entry_balance() RETURNS trigger AS $$
DECLARE
    entry_total NUMERIC;
BEGIN
    SELECT SUM(amount_cents) INTO entry_total
    FROM entry_lines
    WHERE journal_entry_id = NEW.journal_entry_id;

    IF entry_total <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance (sum = %)', NEW.journal_entry_id, entry_total
            USING ERRCODE = 'LDG01';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entry_lines_balance
    AFTER INSERT ON entry_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entry_balance();
