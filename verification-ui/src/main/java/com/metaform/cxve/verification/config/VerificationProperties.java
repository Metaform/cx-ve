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
        TokenSpec management,
        TokenSpec certoAuth,
        ParticipantIdentity participant,
        String inboxAssetId,
        String transferType,
        String ccmApiVersion,
        Timeouts timeouts,
        Duration pollInterval,
        Map<String, Integer> expectedEvents,
        External external) {

    /**
     * What a run against a third-party system needs on top of the above. None of it is derivable:
     * the asset id and the endpoints are the SUT's, agreed up front (see docs/sut-verification.md),
     * and the timeouts bound steps this environment does not drive — the SUT does them in its own
     * time, so they are generous by design and the operator can stop a run instead.
     *
     * <p>{@code expectedEvents} is its own map because most of the managed checklist can never
     * hold here: the identity-provisioning events belong to wallets this environment creates, and
     * the exchange's own events carry the verification participant's context rather than the
     * SUT's. What remains is what the ledger can honestly attribute to an external participant.
     */
    public record External(
            String didWebScheme,
            Duration credentialsTimeout,
            Duration providerOfferTimeout,
            Duration publishTimeout,
            Map<String, Integer> expectedEvents) {
    }

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
