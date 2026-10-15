package com.metaform.cxve.adapter.out.nats;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the NATS/JetStream connection the onboarding lifecycle events are published
 * over.
 *
 * <p>Defaults target the platform: subjects under {@code events.onboarding.} land in the shared
 * {@code edc-events} JetStream stream, and the Vault-delivered NKey seed lives at
 * {@code /vault/secrets/nats.nk}. Disabled by default so the app and tests run without a broker.
 */
@ConfigurationProperties(prefix = "nats")
public record NatsProperties(
        boolean enabled,
        String url,
        String nkeySeedPath
) {

    public NatsProperties {
        if (url == null || url.isBlank()) {
            url = "nats://localhost:4222";
        }
    }

    /** An NKey seed path is optional (absent → connect unauthenticated, e.g. against a local dev NATS). */
    public boolean hasNkeyAuth() {
        return nkeySeedPath != null && !nkeySeedPath.isBlank();
    }
}
