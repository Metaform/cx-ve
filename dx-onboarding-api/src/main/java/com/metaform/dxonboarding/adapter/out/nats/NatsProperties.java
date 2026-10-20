package com.metaform.dxonboarding.adapter.out.nats;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The NATS/JetStream connection the onboarding lifecycle events are published over ({@code nats}).
 * Subjects under {@code events.onboarding.} land in the platform's shared {@code edc-events}
 * stream; the NKey seed is delivered by Vault (the chart's init container).
 *
 * @param enabled      off: the events are not published (local runs, tests)
 * @param nkeySeedPath blank: connect unauthenticated
 */
@ConfigurationProperties(prefix = "nats")
public record NatsProperties(boolean enabled, String url, String nkeySeedPath) {

    public NatsProperties {
        if (url == null || url.isBlank()) {
            url = "nats://localhost:4222";
        }
    }

    public boolean hasNkeyAuth() {
        return nkeySeedPath != null && !nkeySeedPath.isBlank();
    }
}
