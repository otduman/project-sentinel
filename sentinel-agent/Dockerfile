# ── Build stage ──────────────────────────────────────────────────────────────
# Pinned to the same Gradle version as gradle/wrapper/gradle-wrapper.properties.
# When you bump the wrapper, also bump this tag (and the lab-rat Dockerfile)
# and update VERSIONS.md. See VERSIONS.md for the full version policy.
FROM gradle:9.4.1-jdk21-alpine AS builder
WORKDIR /build

COPY settings.gradle .
COPY gradle.properties .
COPY sentinel-core/ sentinel-core/
COPY sentinel-agent/ sentinel-agent/

RUN gradle :sentinel-core:jar :sentinel-agent:bootJar --no-daemon -x test

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S sentinel && adduser -S sentinel -G sentinel \
    && mkdir -p /logs && chown sentinel:sentinel /logs

# Fat JAR for the agent + sentinel-core JAR for the dynamic profiler tool
COPY --from=builder /build/sentinel-agent/build/libs/*.jar app.jar
COPY --from=builder /build/sentinel-core/build/libs/sentinel-core-*.jar sentinel-core.jar

RUN chown sentinel:sentinel app.jar sentinel-core.jar

USER sentinel

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
