# syntax=docker/dockerfile:1

# Build stage: the jar, built by the Maven wrapper. The tests are CI's job (`./mvnw verify`), and the Testcontainers
# suites cannot run inside an image build, so they are skipped here. `sh mvnw` and not `./mvnw`: the build context may
# come from a checkout that did not keep the executable bit.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -Dmaven.test.skip=true package

# Run stage: a JRE, a user that is not root, and a health check on the endpoint the service exposes.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O - http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
