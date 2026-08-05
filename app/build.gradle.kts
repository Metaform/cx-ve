import org.gradle.kotlin.dsl.invoke

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.metaform"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    // NATS/JetStream client + CloudEvents, version-matched to the platform's events-nats bridge
    implementation("io.nats:jnats:2.25.3")
    implementation("io.cloudevents:cloudevents-json-jackson:4.1.1")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

testing {
    suites {
        // Black-box end-to-end tests against a RUNNING VE (scripts/install-ve.sh): pure JUnit
        // sources under src/e2e-test, deliberately without a dependency on the app's classes —
        // they exercise the deployed API through the gateway, not the code in this module.
        // Wire payloads are mirrored locally (see NewParticipantData) to keep it that way.
        // Not wired into `check`/`build` (needs a live cluster); run on demand:
        //   ./gradlew :app:e2eTest
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter()
            dependencies {
                // In a test-suite dependencies block the scope IS the suite — the configuration
                // is `implementation` (mapped to e2eTestImplementation), not testImplementation
                implementation("io.rest-assured:rest-assured:6.0.1")
                // version-less on purpose: the Spring Boot BOM (dependency-management plugin)
                // governs the suite's configurations too, keeping Jackson in lockstep with the app
                implementation("com.fasterxml.jackson.core:jackson-databind")

                implementation("org.awaitility:awaitility:4.3.0")
                // newest stable line; 4.x is still in beta (org.wiremock coordinates, but the
                // Java packages remain com.github.tomakehurst.wiremock). MUST be the standalone
                // (shaded) artifact here: the Spring Boot BOM forces Jetty 12.1 on this
                // configuration, which neither plain wiremock (needs Jetty 11) nor
                // wiremock-jetty12 (built against 12.0, NoSuchMethodError on 12.1) can run on.
                // The standalone jar bundles its own relocated Jetty, out of the BOM's reach.
                implementation("org.wiremock:wiremock-standalone:3.13.2")
                implementation("org.assertj:assertj-core:3.21.0")
            }
            sources {
                java {
                    setSrcDirs(listOf("src/e2e-test/java"))
                }
                resources {
                    setSrcDirs(listOf("src/e2e-test/resources"))
                }
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
