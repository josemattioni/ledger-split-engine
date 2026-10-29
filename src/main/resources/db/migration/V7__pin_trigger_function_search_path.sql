-- V7: every trigger function fixes its own search path.
--
-- Why. The functions of V2 to V6 name entry_lines and journal_entries without a schema and set no search_path, so each
-- ran with the calling session's. Postgres searches the temporary schema first and ledger_app can create temporary
-- tables: a CREATE TEMP TABLE with a ledger table's name made a check read that table instead, and pass (two lines
-- that cancel out added to an old entry, an unbalanced entry committed). pg_restore's empty search_path could not
-- resolve the names either.
--
-- How. ALTER FUNCTION ... SET search_path = public, pg_temp, for every session including the owner. pg_temp is listed,
-- and last, on purpose: left out, it is still searched first for relations. The tables live in public (Flyway's default
-- schema); elsewhere the functions would fail loudly at the first insert. Bodies, triggers, tables and data are unchanged.
--
-- A trigger function added later sets its own search_path in its CREATE FUNCTION (PROJECT-CONVENTIONS.md);
-- TriggerFunctionsPinTheirSearchPathTest fails if one does not.
ALTER FUNCTION check_entry_balance() SET search_path = public, pg_temp;
ALTER FUNCTION forbid_ledger_mutation() SET search_path = public, pg_temp;
ALTER FUNCTION check_entry_has_enough_lines() SET search_path = public, pg_temp;
ALTER FUNCTION check_reversal_matches_original() SET search_path = public, pg_temp;
ALTER FUNCTION check_line_added_with_its_entry() SET search_path = public, pg_temp;
