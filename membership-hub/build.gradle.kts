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
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Postgres persistence for membership records; the "test" profile swaps in the in-memory
    // store (see application.yaml), so the app still runs database-free for local development
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    // In-memory stand-in for Postgres in the repository tests (schema is vanilla JPA DDL)
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

testing {
    suites {
        // Black-box end-to-end tests against a RUNNING VE (scripts/install-ve.sh): pure JUnit
        // sources under src/e2e-test, deliberately without a dependency on the app's classes —
        // they exercise the deployed APIs through the gateway, not the code in this module.
        // They live HERE because the hub is the journey's entry point: the suite onboards
        // through it and only then drives the platform. Wire payloads are mirrored locally
        // (see NewParticipantData, MembershipHubApi) to keep the independence.
        // Not wired into `check`/`build` (needs a live cluster); run on demand:
        //   ./gradlew e2eTest
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
            targets {
                all {
                    testTask.configure {
                        // long-running e2e flows report progress on stdout (TestLog) —
                        // surface it, Gradle swallows test output by default
                        testLogging {
                            showStandardStreams = true
                            events("passed", "failed", "skipped")
                        }
                    }
                }
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
