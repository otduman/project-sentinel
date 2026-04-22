# ── Build stage ──────────────────────────────────────────────────────────────
FROM gradle:8.14-jdk21-alpine AS builder
WORKDIR /build

# Copy project descriptor and only the modules lab-rat needs
COPY settings.gradle .
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
