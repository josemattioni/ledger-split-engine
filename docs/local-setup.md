# Local setup

How to run the application and its tests on one machine. The short version is in the
[README](../README.md#quickstart).

## Prerequisites

- JDK 21
- Docker (for the local Postgres 17 instance and for running the test suite, which uses
  Testcontainers)

## Running locally

Start Postgres:

```bash
docker compose up -d
```

Then start the application:

```bash
./mvnw spring-boot:run
```

Two database roles are involved. Flyway migrates as the **owner**, the bootstrap Postgres
user `ledger_split_engine`, which owns the tables. The application itself connects as
**`ledger_app`**, a role that can read, and insert only the columns a client supplies, and nothing else. `ledger_app` is
created by `docker/initdb/01-create-ledger-app-role.sql` (roles are cluster-level, so Flyway
can't create it; Flyway only grants it privileges). Both are dev-only credentials, set in
`application.yaml`.

> **Init scripts only run on an empty data directory.** If you already have a local
> Postgres volume from an earlier slice, recreate it once so `ledger_app` exists — otherwise
> Flyway fails with `role "ledger_app" does not exist`:
>
> ```bash
> docker compose down -v
> docker compose up -d
> ```
>
> Do the same after editing the init script or an already-applied migration.
>
> **Postgres 17, and the transaction bound.** The database is Postgres 17, because as-of balances depend
> on `transaction_timeout`, which exists from version 17, and the init script gives `ledger_app` a
> default of 30 seconds for it. Recreate your volume once with the commands above. If you skip it, what you see
> depends on the volume. An old **Postgres 16 volume**: the `postgres:17` container exits at start with
> `FATAL: database files are incompatible with server` and `The data directory was initialized by
> PostgreSQL version 16, which is not compatible with this version 17`. A volume created **before the
> init-script change**: the application refuses to start, with a message naming the missing bound and
> `docker compose down -v`.

The app listens on `http://localhost:8080`. Postgres is exposed on **`5433`**, not the
default `5432` — this repo's `docker-compose.yml` deliberately avoids `5432` since it's
a common port for a pre-existing local Postgres install to already occupy. If you hit a
"password authentication failed" error that seems to make no sense (credentials look
right, yet auth fails), check whether something else on your machine is already bound to
whichever port you're using before assuming the application is at fault.

## Running in a container

The application also runs as a container, built from the `Dockerfile` (a multi-stage build on a JRE 21 image, running as
a user that is not root). It is an opt-in compose service, so a plain `docker compose up -d` still starts Postgres only:

```bash
docker compose --profile app up -d --build --wait
curl http://localhost:8080/actuator/health
```

The second command answers `{"status":"UP"}`. The container waits for Postgres to be healthy, has its own health check
on that endpoint, and `docker compose --profile app logs app` shows its log. Stop it, and remove the data, with:

```bash
docker compose --profile app down -v
```

The application reads its settings from the environment (Spring Boot maps `SPRING_DATASOURCE_URL` to
`spring.datasource.url`, and so on). The compose file takes these from the shell or an `.env` file, and the
development values are the defaults:

| Variable | Default | What it sets |
|---|---|---|
| `POSTGRES_DB` | `ledger_split_engine` | the database, for Postgres and the application |
| `POSTGRES_USER` | `ledger_split_engine` | the owner: Postgres' bootstrap user and the one Flyway migrates as |
| `POSTGRES_PASSWORD` | `ledger_split_engine` | the owner's password (Postgres applies it only to an empty data directory) |
| `LEDGER_APP_USER` | `ledger_app` | the role the application connects as |
| `LEDGER_APP_PASSWORD` | `ledger_app` | that role's password, **which has to match the one the init script gave it** |

The `ledger_app` role and its password come from `docker/initdb/01-create-ledger-app-role.sql`, not from the
environment: to give the role another password, change the script (and recreate the volume) as well as the variable.
A value that does not match shows up as the application exiting at start with `password authentication failed for user
"ledger_app"`.

`/actuator/health` is the service's only operational endpoint: the status and nothing else, `503` with
`{"status":"DOWN"}` within about four seconds when the database cannot be reached. It is outside `/api/v1` on purpose
(see `PROJECT-CONVENTIONS.md`).

## What CI runs

`.github/workflows/ci.yml` runs on every push to `main` or `master` and on pull requests, in two parallel jobs on
`ubuntu-latest`: `verify` runs `./mvnw -B verify` (Temurin 21; Testcontainers uses the runner's Docker) and keeps the
test reports if it fails, and `image` builds the container with `docker compose --profile app up -d --build --wait` and
checks that `/actuator/health` answers `UP`. `./mvnw` has to be committed with its executable bit
(`git add --chmod=+x mvnw`; this checkout has `core.filemode=false`).

## Running the tests

```bash
./mvnw verify
```

`verify` runs both suites: the unit and integration tests (`src/test`), then the **contract suite**
(`src/contractTest`), black-box HTTP tests written by an agent in the QA role from the specs and the conventions
without reading the application's source (see [How this was built](how-this-was-built.md)), so they hold the API to what
it promises. (`./mvnw test` runs only the first.) The contract suite belongs to that QA role: a failing contract test is
reported, not edited away. It has no skipped tests: the requirements that are not visible over HTTP (the database's
privileges, the application refusing to start, a log line) are mapped, each to the test that covers it, in
[requirements-below-http.md](requirements-below-http.md).

Docker must be running — every test that needs a database uses Testcontainers to run against a real
Postgres instance with real Flyway migrations applied, not a mocked persistence layer. The
test container creates `ledger_app` from the same init script docker-compose uses, so tests
run as the real restricted application role, with its transaction bound; run them from the project root so that
script is found. No `docker compose up` is needed to run the tests, only to run the application.
