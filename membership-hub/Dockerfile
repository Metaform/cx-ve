# syntax=docker/dockerfile:1

# Builds the Catena-X Membership Hub from source. Alternative to the buildpacks image
# (./gradlew bootBuildImage); build from this directory with:  docker build -t membership-hub .

# ---- Build the Spring Boot jar from source ----
# Pinned to $BUILDPLATFORM: the jar is architecture-independent, so in a multi-platform build
# (linux/amd64 + linux/arm64) this stage and the extract stage run ONCE on the builder's native
# arch instead of repeating the Gradle build under QEMU emulation; only the runtime stage is
# per-platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/

# Cache mount keeps the Gradle distribution + dependency cache across image builds
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon bootJar

# ---- Extract the jar into the layered layout (per the Spring Boot Dockerfile reference) ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jre-jammy AS extract
WORKDIR /builder

COPY --from=build /workspace/build/libs/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /application

RUN useradd --system --uid 1001 --no-create-home hub

# One image layer per Boot layer, least- to most-frequently changing, so a code-only
# change re-pulls just the application layer
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./

USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
