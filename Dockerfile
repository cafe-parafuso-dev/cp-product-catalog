# syntax=docker/dockerfile:1.7
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21-alpine
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

# Cache mount id below is Railway's non-standard requirement: id=s/<service-id>-<path>,
# with the service id hardcoded (build args/env vars are rejected in this field:
# https://station.railway.com/questions/cache-mount-id-is-not-prefixed-with-cach-456d2f50).
# Plain `docker build` elsewhere treats it as an opaque string, so this stays portable.
# 9a2618b0-cdbc-4c4e-bd2a-7cc6acd1cd40 is hb-catalog-service's id in the "hubinity"
# Railway project — update it if this Dockerfile is ever reused for a different service.

# Shared contracts (contracts-catalog, contracts-events) resolve from
# platform-shared-contracts' GitHub Packages registry at build time — see
# docs/adr/0013-consume-contracts-via-github-packages.md (supersedes ADR
# 0012's git-subtree vendoring, which existed only because that registry's
# publish pipeline looked unavailable at the time; it had already shipped).
# GITHUB_USERNAME/GITHUB_TOKEN (read:packages) must be set as Railway
# *build-time* variables — see settings.xml for details.
ARG GITHUB_USERNAME
ARG GITHUB_TOKEN
ENV GITHUB_USERNAME=${GITHUB_USERNAME} \
    GITHUB_TOKEN=${GITHUB_TOKEN}
COPY settings.xml ./settings.xml

# Cache deps first
COPY pom.xml .
RUN --mount=type=cache,id=s/9a2618b0-cdbc-4c4e-bd2a-7cc6acd1cd40-/root/.m2,target=/root/.m2 mvn -B -ntp -q -s settings.xml dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,id=s/9a2618b0-cdbc-4c4e-bd2a-7cc6acd1cd40-/root/.m2,target=/root/.m2 mvn -B -ntp -q -s settings.xml -DskipTests package spring-boot:repackage

FROM ${JRE_IMAGE} AS runtime
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /workspace/target/hb-catalog-service-*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
