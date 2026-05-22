# Same pattern as lab-rat / order-service — pinned Gradle base + JRE runtime.
# When you bump the Gradle wrapper version, also bump this tag and update VERSIONS.md.
FROM gradle:9.4.1-jdk21-alpine AS builder
WORKDIR /build

COPY settings.gradle .
COPY gradle.properties .
COPY payment-service/ payment-service/

RUN gradle :payment-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /logs && chown appuser:appgroup /logs

COPY --from=builder /build/payment-service/build/libs/*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
