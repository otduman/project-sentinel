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

# Create log directory (mounted as a shared Docker volume)
RUN mkdir -p /logs

COPY --from=builder /build/lab-rat/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
