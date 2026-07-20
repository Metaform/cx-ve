# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Repository Layout

Monorepo with three top-level areas:

- `OnboardingApi/` — Spring Boot 4.x application (Java 17, Gradle Kotlin DSL, package `com.beardyinc.cxve`). The Gradle root is the repo root; the app is the `:OnboardingApi` module.
- `charts/cx-ve/` — Helm chart for the application. Service port is 8080; liveness/readiness probes point at Spring Actuator (`/actuator/health/liveness`, `/actuator/health/readiness`).
- `scripts/` — utility scripts.

## Commands

```shell
./gradlew build                            # compile + all tests
./gradlew :OnboardingApi:test              # tests for the app module only
./gradlew :OnboardingApi:test --tests "com.beardyinc.cxve.CxVeApplicationTests"   # single test class
./gradlew :OnboardingApi:bootRun           # run locally on port 8080
./gradlew :OnboardingApi:bootBuildImage    # build OCI image via buildpacks (no Dockerfile)
helm lint charts/cx-ve           # validate the Helm chart
helm template charts/cx-ve       # render chart manifests locally
```

## Notes

- The container image is built with Spring Boot buildpacks (`bootBuildImage`), not a Dockerfile. The chart's `image.repository` default is `cx-ve`.
- Spring Boot 4.x uses `spring-boot-starter-webmvc` (not the old `spring-boot-starter-web`) and split test starters (`spring-boot-starter-webmvc-test`, `spring-boot-starter-actuator-test`).
