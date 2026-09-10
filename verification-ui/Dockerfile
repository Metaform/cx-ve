# syntax=docker/dockerfile:1

# Builds the CX-VE Verification UI (Angular frontend + Spring Boot BFF) from source. Build from
# this directory with:  docker build -t verification-ui .

# ---- Build the Angular frontend ----
# Pinned to $BUILDPLATFORM (like every compile stage): the dist is architecture-independent, so
# a multi-platform build runs npm once on the builder's native arch instead of under QEMU.
FROM --platform=$BUILDPLATFORM node:22-alpine AS frontend
WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY frontend/ ./
# angular.json's production configuration sets base-href /ui/ (the gateway route prefix)
RUN npm run build

# ---- Build the Spring Boot jar from source, with the frontend as static resources ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/
COPY --from=frontend /workspace/frontend/dist/verification-ui/browser/ src/main/resources/static/

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

RUN useradd --system --uid 1001 --no-create-home ui

# One image layer per Boot layer, least- to most-frequently changing, so a code-only
# change re-pulls just the application layer
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./

USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
