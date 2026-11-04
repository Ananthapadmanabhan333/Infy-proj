# syntax=docker/dockerfile:1
# SA API — Java 21 / Spring Boot. Build context: this directory (backend/).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Resolve dependencies in their own layer so source edits do not re-download them.
RUN mvn -B -q dependency:go-offline -DskipTests
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/nexus360-api.jar /app/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system nexus && useradd --system --gid nexus --home /app nexus
COPY --from=build --chown=nexus:nexus /app/app.jar app.jar
USER nexus
# Size the heap from the container limit (Render free/starter instances have 512 MB) and fail fast on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
# Render injects PORT; application.yml binds server.port to it (default 8080).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
