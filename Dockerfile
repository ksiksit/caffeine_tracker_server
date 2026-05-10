# ===== Stage 1: Build =====
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Cache dependencies first
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

# Build the JAR
COPY src ./src
RUN gradle bootJar --no-daemon -x test


# ===== Stage 2: Runtime =====
FROM eclipse-temurin:21-jre-alpine

ENV TZ=Asia/Seoul

# Run as non-root user
RUN addgroup -S app && adduser -S app -G app
USER app

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
