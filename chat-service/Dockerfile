# --- Stage 1: build ---
# See user-service/Dockerfile for the full rationale of each step — this file
# mirrors it exactly. Same Gradle version as gradle/wrapper/gradle-wrapper.properties
# so the jar built here matches a local `./gradlew build`.
FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /app

# Dependency-resolution layer, cached separately from source so source-only changes
# don't force re-downloading dependencies on every rebuild.
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon

COPY src ./src
RUN gradle bootJar -x test --no-daemon

# --- Stage 2: runtime ---
# JRE-only final image — no JDK/Gradle/build cache carried into the runtime image.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Gradle's output lives under build/libs/ (Maven's was target/); wildcard avoids
# hardcoding the versioned jar filename.
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]