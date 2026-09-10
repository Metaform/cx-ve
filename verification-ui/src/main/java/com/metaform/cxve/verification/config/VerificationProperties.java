package com.metaform.cxve.verification.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything a verification run is parameterized by. The expected-events map (subject → minimum
 * count) is deliberately config, not code: the counts encode assumptions about which side's
 * controlplane events carry the participant-under-test's context id, and tuning them must not
 * require a rebuild.
 */
@ConfigurationProperties(prefix = "verification")
public record VerificationProperties(
        String dspBaseUrl,
        String certoAssetBaseUrl,
        TokenSpec management,
        TokenSpec certoAuth,
        ParticipantIdentity participant,
        String inboxAssetId,
        String transferType,
        Timeouts timeouts,
        Duration pollInterval,
        Map<String, Integer> expectedEvents) {

    /** The jwtlet mapping (RFC 8693 {@code resource}) and scope a token is exchanged under. */
    public record TokenSpec(String tokenResource, String tokenScope) {
    }

    /** The permanent verification participant's fixed identity. */
    public record ParticipantIdentity(String name, String shortName, String bpn, String vatId) {
    }

    public record Timeouts(
            Duration onboarding,
            Duration catalog,
            Duration negotiation,
            Duration transfer,
            Duration certo,
            Duration events) {
    }

    /** The provider's DSP endpoint for a participant context, as the consumer side dials it. */
    public String dspAddressOf(String providerPcid) {
        return "%s/%s/cx-neptune".formatted(dspBaseUrl, providerPcid);
    }
}
