## Why

The build is green and takes two minutes (783 unit tests, 937 contract invocations), but nothing runs it except this machine, so "it works on someone else's machine" is unproven. The application has no health endpoint (`GET /actuator/health` answers 404), which is the least operability a Spring Boot service is expected to have, and no image: `docker compose up` starts only Postgres, so running the service needs a JDK and Maven. This is review finding F10. It is cheap now: `mvnw` is already staged as executable (F1), which a Linux CI run depends on, and the README quickstart is verified.

## What Changes

- **CI.** `.github/workflows/ci.yml`: on push and pull request, `ubuntu-latest` (Docker is there, so Testcontainers works), Temurin 21 with the Maven cache, `./mvnw -B verify`, and the Surefire and Failsafe reports uploaded when it fails. A second job builds the image and checks the health endpoint through compose. No README badge yet: the repository has no remote, so its URL is unknown.
- **Health.** `spring-boot-starter-actuator` with only `health` exposed, at `/actuator/health`, showing the status and nothing else (no details). It includes the database, checked with a validity call that has its own timeout (Boot's own check hangs on a database that is frozen rather than refusing connections), so it is `DOWN` (503) within seconds when Postgres cannot be reached. The other built-in contributors (disk space) are off, because a full disk on a developer's laptop is not the service being down. This is the first endpoint outside `/api/v1` and the first whose responses are not `application/problem+json`: `PROJECT-CONVENTIONS.md` gets a carve-out saying so.
- **Image.** A multi-stage `Dockerfile` (build with the Maven wrapper, run on a JRE 21 as a non-root user, with a container health check on the endpoint above), a `.dockerignore`, and the application as a compose service under a profile, so the existing `docker compose up -d` still starts Postgres only and the quickstart does not change. The service takes its datasource URL and credentials from environment variables (the current development values stay as defaults) and starts after Postgres reports healthy.
- **Docs.** `docs/local-setup.md` (running the container), `PROJECT-CONVENTIONS.md` (the carve-out), and a few lines in the README, which is at 144 of its 150 lines.
- **Not in this change**, recorded in design.md: OpenAPI (the README table covers the API); any other actuator endpoint, metrics, or a separate management port; authentication; publishing the image to a registry; the CI badge (needs the remote); pinning the workflow's actions by commit hash.

## Capabilities

### New Capabilities

- `service-health`: the application reports whether it can serve requests, from outside and without credentials: up when it can reach its database, down when it cannot, with no detail exposed and no other operational endpoint reachable.

### Modified Capabilities

None. No behavior under `/api/v1` changes.

## Impact

- **Build and config:** `pom.xml` (one dependency), `application.yaml` (a few `management.*` lines and the pool's connection timeout), and one small class for the bounded database check.
- **New files:** `.github/workflows/ci.yml`, `Dockerfile`, `.dockerignore`; `docker-compose.yml` gains the `app` service under a profile.
- **Tests:** a new integration test over real HTTP for the health endpoint and for what is not exposed. `src/contractTest` is QA's and is not touched; its result is expected to stay at 937 invocations, 29 skipped, 0 failures.
- **Docs:** `PROJECT-CONVENTIONS.md`, `docs/local-setup.md`, `README.md`.
- **Specs:** a new `openspec/specs/service-health/spec.md` at archive time.
