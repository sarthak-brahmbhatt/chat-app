# --- Stage 1: build ---
# Uses the official Gradle image (which bundles a matching JDK) so the build doesn't
# depend on anything installed on the host — same reasoning as the old Maven image.
# Pinned to the same Gradle version as gradle/wrapper/gradle-wrapper.properties so the
# jar produced here matches what a local `./gradlew build` would produce.
FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /app

# Copy only the build definition first (not source yet). Docker caches each
# RUN/COPY layer keyed on its inputs, so as long as build.gradle/settings.gradle
# don't change, this dependency-resolution layer is reused on rebuilds even when
# only application code changes below — this is the direct equivalent of the old
# `COPY pom.xml . && mvn dependency:go-offline` trick.
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon

# Now copy the actual source and build the executable jar. `bootJar` is the Spring
# Boot Gradle plugin's task for producing the runnable fat-jar (equivalent of
# Maven's `package` + spring-boot-maven-plugin repackage). Tests are skipped here
# (-x test) because this is a packaging build, not a verification step — the same
# behavior as the old `-DskipTests`.
COPY src ./src
RUN gradle bootJar -x test --no-daemon

# --- Stage 2: runtime ---
# A separate, much smaller final image: only a JRE (no JDK, no Gradle, no build
# cache) plus the single jar produced above. Keeping build tooling out of the
# runtime image is the whole point of a multi-stage build — smaller image, smaller
# attack surface.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Gradle's default output path is build/libs/ (Maven's was target/). The jar name
# comes from settings.gradle's rootProject.name + version, e.g. user-service-0.0.1-SNAPSHOT.jar
# — the wildcard avoids hardcoding that full name here.
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]