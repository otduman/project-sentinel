# ── Build stage ──────────────────────────────────────────────────────────────
# Pinned to the same Gradle version as gradle/wrapper/gradle-wrapper.properties.
# When you bump the wrapper, also bump this tag (and the sentinel-agent
# Dockerfile) and update VERSIONS.md.
FROM gradle:9.4.1-jdk21-alpine AS builder
WORKDIR /build

COPY settings.gradle .
COPY gradle.properties .
COPY lab-rat/ lab-rat/

RUN gradle :lab-rat:bootJar --no-daemon -x test

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /logs && chown appuser:appgroup /logs

COPY --from=builder /build/lab-rat/build/libs/*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
