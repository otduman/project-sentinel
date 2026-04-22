# ── Build stage ──────────────────────────────────────────────────────────────
FROM gradle:8.14-jdk21-alpine AS builder
WORKDIR /build

COPY settings.gradle .
COPY sentinel-core/ sentinel-core/
COPY sentinel-agent/ sentinel-agent/

RUN gradle :sentinel-core:jar :sentinel-agent:bootJar --no-daemon -x test

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN mkdir -p /logs

# Fat JAR for the agent + sentinel-core JAR for the dynamic profiler tool
COPY --from=builder /build/sentinel-agent/build/libs/*.jar app.jar
COPY --from=builder /build/sentinel-core/build/libs/sentinel-core-*.jar sentinel-core.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
