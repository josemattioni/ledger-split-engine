# How this was built

The application, its tests and the OpenSpec artifacts were written by AI coding agents. The scope, the design
decisions and the decision to accept each change were the project owner's.

The work followed OpenSpec's spec-driven flow: a brief became a change (proposal, delta specs, design, tasks) that was
reviewed, applied, and archived with its specs merged into `openspec/specs/`. `openspec/changes/archive/` holds every
change, with the reasoning behind it and the alternatives that were turned down. `.claude/` holds the slash commands and
skills that `openspec init` generated for Claude Code (`/opsx:propose`, `/opsx:apply`, `/opsx:archive`); they are
workflow tooling, not part of the application.

Three roles, each an agent working under its own rule:

- **Developer** (Claude Code): the application, the migrations, the tests in `src/test` and the OpenSpec artifacts. It
  does not edit `src/contractTest`.
- **QA**: the contract suite in `src/contractTest`, written from the specs and `PROJECT-CONVENTIONS.md` without
  reading the application's source, so it is a second reading of the specs and not a mirror of the code. It reports
  failures; it does not fix the application.
- **Reviewer**: reads the finished code and reports defects. `harden-request-validation`, `close-final-review-gaps`,
  `pin-trigger-search-path` and `add-ci-health-and-container-image` came from its reports.

A failing contract test is a report to the owner, not an edit to the test. When the test was right the application was
fixed (the twelve account-registration failures were closed that way, none by changing a test); a test was edited only
with the owner's explicit say-so, for tests that encoded behavior a later spec had retired and for harness plumbing.
An early example of why the suite is written apart from the code: the request type bound `ownerRef` instead of
`owner_ref`, and every test written alongside the code shared the mistake until the owner's own `curl` call found it.

Calls that were the owner's:

- Build the ledger core first. Split, settlement and fee calculation are left out on purpose.
- Money is integer centavos. Surrogate keys are `BIGINT` identities, not UUIDs, and never leave the database.
- Reject, don't normalize: a padded or blank value is a `400`, never trimmed and stored.
- Accounts are immutable. A mistyped account is corrected with entries, accepting that there is no account status
  (see [Correcting an account](api-rules.md#correcting-an-account)).
- Requests that Tomcat rejects before the application sees them keep Tomcat's own error page. A problem+json valve was
  prototyped and left out, and the exception is written into the specs.
- A deep edge case that was not worth chasing is documented as a limit instead (see [Scope and known limitations](../README.md#scope-and-known-limitations)).
- The cost of a balance read on a very large account was measured and documented, not built around: the index, the
  checkpoints and the other options are written down with what each costs
  (see [Balance reads and scale](scope-and-limits.md#balance-reads-and-scale)).
