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
    // OAuth2 resource server: bearer-JWT validation against the VE's OSP IdP (Ory Hydra)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    // NATS/JetStream client + CloudEvents for the onboarding lifecycle events, version-matched to
    // the cx-onboarding-api and the platform's events-nats bridge
    implementation("io.nats:jnats:2.25.3")
    implementation("io.cloudevents:cloudevents-json-jackson:4.1.1")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // jwt() request post-processors for the controller tests
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
