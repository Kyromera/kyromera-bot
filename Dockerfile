# Build stage
FROM gradle:8.7.0-jdk17 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/kyromera.jar ./kyromera.jar
RUN mkdir -p /app/logs /app/config
ENV CONFIG_SOURCE=env
ENTRYPOINT ["java", "-jar", "/app/kyromera.jar"]