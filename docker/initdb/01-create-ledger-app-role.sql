-- Creates the role the application connects as. Roles are cluster-level, not schema objects,
-- so this lives in an init script (run by the Postgres entrypoint on an empty data directory)
-- rather than in a Flyway migration. The same file is used by docker-compose and by the
-- Testcontainers-backed tests.
--
-- Privileges on the ledger tables are granted by Flyway: SELECT, and INSERT only on the columns a client
-- supplies (V3 granted table-level INSERT; V5 narrowed it to column-level).
-- The dev password below is intentionally a throwaway; it is mirrored in application.yaml.
CREATE ROLE ledger_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD 'ledger_app';

-- A transaction of the application's role ends after 30 seconds, committed or not (PostgreSQL 17+), unless the
-- session changes the setting. This is what makes an as-of balance stable: an entry's created_at is the start
-- of its transaction, so once a moment is 60 seconds old every entry recorded before it has either committed or
-- been abandoned. 30 seconds is half of that settle window (domain SettleWindow), the other half being the
-- safety margin.
-- It is a DEFAULT, not a restriction: ALTER ROLE ... SET applies to each new session of ledger_app, and a role
-- may change its own session settings, so ledger_app can SET transaction_timeout = 0 (a test shows it). The
-- guarantee holds because the application keeps the default (it never changes the setting); it assumes a
-- correct application and is not a defence against whoever holds this role's credentials. The application
-- refuses to start if its role does not carry the default (TransactionBoundGuard, checked once, at startup).
-- The owner stays unbounded because Flyway needs that. Like the role itself it only takes effect on an empty
-- data directory.
ALTER ROLE ledger_app SET transaction_timeout = '30s';
