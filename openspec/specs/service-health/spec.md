# service-health Specification

## Purpose
Lets an operator or an orchestrator ask the running service whether it can serve requests, without credentials and without the answer disclosing anything about the deployment, and keeps every other operational endpoint out of reach.

## Requirements

### Requirement: The service reports whether it can serve requests
The system SHALL answer `GET /actuator/health` for any client, without credentials. The answer SHALL be `200` with a body whose `status` is `UP` when the service can reach its database, and `503` with a body whose `status` is `DOWN` when it cannot. The body SHALL contain the `status` and nothing else: no component, host, credential, version or error text. The answer is the health document itself and not `application/problem+json`; every other error response of the service keeps that format. A database that comes back SHALL make the endpoint answer `UP` again without restarting the service.

#### Scenario: The database answers
- **WHEN** a client requests `GET /actuator/health` while the service can reach its database
- **THEN** the answer is `200` and the body is `{"status":"UP"}`

#### Scenario: The database cannot be reached
- **WHEN** a client requests `GET /actuator/health` while the service cannot reach its database
- **THEN** the answer is `503` and the body is `{"status":"DOWN"}`, and it does not take longer than a few seconds to arrive

#### Scenario: The database comes back
- **WHEN** the database that was unreachable can be reached again and a client requests `GET /actuator/health`
- **THEN** the answer is `200` with `{"status":"UP"}`, without the service having been restarted

#### Scenario: The answer discloses nothing
- **WHEN** a client requests `GET /actuator/health`, whatever the state of the service
- **THEN** the body has `status` as its only member

#### Scenario: No credentials are asked for
- **WHEN** a client requests `GET /actuator/health` with no `Authorization` header and no cookie
- **THEN** the answer is the health document, not a refusal

### Requirement: No other operational endpoint is reachable
The system SHALL NOT expose any operational endpoint other than the health endpoint: not the index of endpoints, and not the environment, configuration properties, metrics, beans, request mappings, loggers, thread or heap dumps, or application information. A request for any of them SHALL be answered as for any path the service does not have.

#### Scenario: The index of operational endpoints is not served
- **WHEN** a client requests `GET /actuator`
- **THEN** the answer is `404`

#### Scenario: Endpoints that would disclose configuration are not served
- **WHEN** a client requests `GET /actuator/env`, `GET /actuator/configprops`, `GET /actuator/beans`, `GET /actuator/mappings`, `GET /actuator/loggers`, `GET /actuator/metrics`, `GET /actuator/heapdump`, `GET /actuator/threaddump` or `GET /actuator/info`
- **THEN** every answer is `404`
