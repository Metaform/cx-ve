# syntax=docker/dockerfile:1

# Builds the Onboarding API (:app module) from source. Alternative to the buildpacks image
# (./gradlew :app:bootBuildImage); build with:  docker build -t cx-ve .

# ---- Build the Spring Boot jar from source ----
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts ./
COPY gradle/ gradle/
COPY app/build.gradle.kts app/build.gradle.kts
COPY app/src/ app/src/

# Cache mount keeps the Gradle distribution + dependency cache across image builds
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon :app:bootJar

# ---- Extract the jar into the layered layout (per the Spring Boot Dockerfile reference) ----
FROM eclipse-temurin:17-jre-jammy AS extract
WORKDIR /builder

COPY --from=build /workspace/app/build/libs/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /application

RUN useradd --system --uid 1001 --no-create-home onboarding

# One image layer per Boot layer, least- to most-frequently changing, so a code-only
# change re-pulls just the application layer
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./

USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
