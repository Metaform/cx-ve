package com.beardyinc.cxve.infrastructure.nats;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the NATS/JetStream subscriber that consumes IdentityHub issuance events.
 *
 * <p>Defaults target the platform: the shared {@code edc-events} JetStream stream, subject prefix
 * {@code events.} (see the EDC {@code events-nats} bridge), and the Vault-delivered NKey seed at
 * {@code /vault/secrets/nats.nk}. Disabled by default so the app and tests run without a broker.
 */
@ConfigurationProperties(prefix = "nats")
public record NatsProperties(
        boolean enabled,
        String url,
        String stream,
        String durableName,
        String subjectFilter,
        String nkeySeedPath
) {

    public NatsProperties {
        if (url == null || url.isBlank()) {
            url = "nats://localhost:4222";
        }
        if (stream == null || stream.isBlank()) {
            stream = "edc-events";
        }
        if (durableName == null || durableName.isBlank()) {
            durableName = "onboarding-api";
        }
        if (subjectFilter == null || subjectFilter.isBlank()) {
            subjectFilter = "events.issuance.>";
        }
    }

    /** An NKey seed path is optional (absent → connect unauthenticated, e.g. against a local dev NATS). */
    public boolean hasNkeyAuth() {
        return nkeySeedPath != null && !nkeySeedPath.isBlank();
    }
}
