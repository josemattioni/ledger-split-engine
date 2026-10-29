# Architecture

Hexagonal: the domain package (`com.github.josemattioni.ledger_split_engine.domain`) has
no framework dependencies. Writes go through a use case in the `application` package
(`PostJournalEntry`, and `ReverseJournalEntry` on top of it), which decides what the port reports: the persistence adapter says
whether an idempotency key was new, and the use case tells a retry from a reuse. A plain read calls the
port directly; a read that applies a ledger rule goes through a use case too (`ReadAccountBalance` applies
the settle window, and asks the database for its own time before it reads the sum). Persistence is a plain JDBC adapter (`JdbcTemplate`),
not JPA — Flyway is the sole owner of the schema. The balanced-sum invariant on a journal
entry is enforced twice: once in the domain before any row is inserted, and once more by a
deferred Postgres trigger as a backstop; the degenerate-entry rules follow the same
pattern. Concurrent posts with one key are serialized by the unique index on the key
(`INSERT … ON CONFLICT DO NOTHING`), so no lock beyond the index is needed and the
application role needs no `UPDATE` privilege. A reversal adds one column, `reverses_entry_id`, on the
reversal's own row (the original is never touched), and "at most once" is a unique constraint, not a check: a
request that races with its own retry can be refused by that constraint first, so the use case asks once more
and the key then decides.

**Where things are**

- `src/main`: the application (`domain`, `application`, `adapter.web`, `adapter.persistence`, `config`).
- `Dockerfile` and `docker-compose.yml`: the application's image (opt-in compose service) and the Postgres it runs
  against; `.github/workflows/ci.yml`: the build and the image check, run on every push. See
  [docs/local-setup.md](local-setup.md).
- `src/test`: unit and integration tests (Testcontainers). `src/contractTest`: the black-box contract suite (written by the QA agent, see [How this was built](how-this-was-built.md)).
- `openspec/specs/`: what the system promises, requirement by requirement. `PROJECT-CONVENTIONS.md`: the rules that hold
  for every endpoint, migration and slice.
- `openspec/changes/archive/`: how each slice was proposed, designed and built. The design of each, in order:
  [accounts and skeleton](../openspec/changes/archive/2026-10-08-accounts-and-skeleton/design.md),
  [journal entries](../openspec/changes/archive/2026-10-06-add-journal-entries/design.md),
  [append-only ledger and entry guards](../openspec/changes/archive/2026-10-08-append-only-ledger-and-entry-guards/design.md),
  [idempotent replay](../openspec/changes/archive/2026-10-09-idempotent-replay/design.md),
  [request validation hardening](../openspec/changes/archive/2026-10-15-harden-request-validation/design.md),
  [entry reversal](../openspec/changes/archive/2026-10-21-entry-reversal/design.md),
  [immutable accounts and as-of balances](../openspec/changes/archive/2026-10-23-immutable-accounts-and-as-of-balance/design.md),
  [final review fixes](../openspec/changes/archive/2026-10-26-close-final-review-gaps/design.md),
  [pinned trigger search paths](../openspec/changes/archive/2026-10-27-pin-trigger-search-path/design.md) and
  [CI, the health check and the container image](../openspec/changes/archive/2026-10-28-add-ci-health-and-container-image/design.md).
  They hold the reasoning behind the code and the schema.
