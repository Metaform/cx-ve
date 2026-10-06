package com.metaform.cxve.verification.config;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything a verification run is parameterized by: the platform-level settings every run shares,
 * and one {@link DataspaceProfile} per dataspace a run can verify against — the DSP profile,
 * policies, member-id format and use cases that make a dataspace what it is. The expected-events
 * maps (subject → minimum count) are deliberately config, not code: the counts encode assumptions
 * about which side's controlplane events carry the participant-under-test's context id, and
 * tuning them must not require a rebuild.
 */
@ConfigurationProperties(prefix = "verification")
public record VerificationProperties(
        String dspBaseUrl,
        TokenSpec management,
        TokenSpec certoAuth,
        String transferType,
        String credentialDeliverySubject,
        Timeouts timeouts,
        Duration pollInterval,
        External external,
        Map<String, DataspaceProfile> dataspaces) {

    public VerificationProperties {
        // insertion-ordered: the configured order is the order the UI offers them in
        dataspaces = dataspaces == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(dataspaces));
        // Each dataspace's verification participant is a participant of its own, and two things of
        // theirs must not coincide: the short name forms the hosted DID (the hub deploys a DID only
        // once), and the inbox asset id lives in the control plane, whose ids are unique across ALL
        // participant contexts. A clash would only surface at the second participant's ensure.
        requireDistinct(dataspaces, "verification-participant.short-name", profile ->
                profile.verificationParticipant() == null ? List.of() : List.of(profile.verificationParticipant().shortName()));
        requireDistinct(dataspaces, "use-cases.*.ccm.inbox-asset-id", profile -> profile.useCases().values().stream()
                .filter(useCase -> useCase.ccm() != null && useCase.ccm().inboxAssetId() != null)
                .map(useCase -> useCase.ccm().inboxAssetId())
                .toList());
    }

    private static void requireDistinct(Map<String, DataspaceProfile> dataspaces, String key,
                                        Function<DataspaceProfile, List<String>> values) {
        var owners = new HashMap<String, String>();
        dataspaces.forEach((id, profile) -> values.apply(profile).forEach(value -> {
            var owner = owners.putIfAbsent(value, id);
            if (owner != null && !owner.equals(id)) {
                throw new IllegalStateException("verification.dataspaces: %s '%s' is used by both %s and %s — it must differ"
                        .formatted(key, value, owner, id));
            }
        }));
    }

    /**
     * What a run against a third-party system needs on top of the above. None of it is derivable:
     * the endpoints are the SUT's, agreed up front (see docs/sut-verification.md), and the timeouts
     * bound steps this environment does not drive — the SUT does them in its own time, so they are
     * generous by design and the operator can stop a run instead.
     */
    public record External(
            String didWebScheme,
            Duration credentialsTimeout,
            Duration providerOfferTimeout,
            Duration publishTimeout) {
    }

    /** The jwtlet mapping (RFC 8693 {@code resource}) and scope a token is exchanged under. */
    public record TokenSpec(String tokenResource, String tokenScope) {
    }

    public record Timeouts(
            Duration onboarding,
            Duration catalog,
            Duration negotiation,
            Duration transfer,
            Duration certo,
            Duration events) {
    }

    /**
     * One dataspace, as a verification run sees it.
     *
     * @param displayName             e.g. "Catena-X"
     * @param memberId                how the dataspace names and formats a member's id (Catena-X: the BPN)
     * @param dspProfile              the DSP dataspace profile catalog, negotiation and transfer requests
     *                                use, and the last segment of a provider's DSP address
     * @param policyContext           the JSON-LD context the dataspace's policy vocabulary lives in
     * @param accessConstraints       the access policy of an offer this environment seeds
     * @param contractConstraints     the contract (use) policy of an offer this environment seeds
     * @param registrationTemplate    the dataspace-specific {@code registration} object of a hub
     *                                member request, as JSON; text values may carry the placeholders
     *                                {@code {{name}}}, {@code {{shortName}}}, {@code {{memberId}}} and
     *                                {@code {{uniqueId}}}
     * @param verificationParticipant the dataspace's permanent verification participant
     * @param useCases                the use cases a run can verify in this dataspace, by id
     */
    public record DataspaceProfile(
            String displayName,
            MemberId memberId,
            String dspProfile,
            String policyContext,
            List<PolicyConstraint> accessConstraints,
            List<PolicyConstraint> contractConstraints,
            String registrationTemplate,
            ParticipantIdentity verificationParticipant,
            Map<String, UseCase> useCases) {

        public DataspaceProfile {
            accessConstraints = accessConstraints == null ? List.of() : List.copyOf(accessConstraints);
            contractConstraints = contractConstraints == null ? List.of() : List.copyOf(contractConstraints);
            useCases = useCases == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(useCases));
        }

        /** @throws NoSuchElementException for a use case this dataspace does not list */
        public UseCase useCase(String id) {
            var useCase = useCases.get(id);
            if (useCase == null) {
                throw new NoSuchElementException("Use case '%s' is not configured for %s".formatted(id, displayName));
            }
            return useCase;
        }
    }

    /**
     * @param label   what the dataspace calls a member id, e.g. "BPN"
     * @param pattern a regular expression a member id must match in full; blank accepts anything
     * @param example a sample value, shown as the input's placeholder
     */
    public record MemberId(String label, String pattern, String example) {
    }

    /** A policy constraint; the leftOperand is resolved against the dataspace's policy context. */
    public record PolicyConstraint(String leftOperand, String operator, String rightOperand) {
    }

    /** A permanent verification participant's fixed identity. Its member id is its rediscovery key. */
    public record ParticipantIdentity(String name, String shortName, String memberId, String uniqueId) {
    }

    /**
     * A use case of a dataspace. {@code enabled: false} lists it without letting runs start —
     * how a use case the dataspace has but this environment cannot verify yet is shown.
     * {@code ccm} carries the settings of the CCM verification and is required for it.
     */
    public record UseCase(String displayName, boolean enabled, Ccm ccm) {
    }

    /**
     * The CCM (company certificate management) verification in one dataspace.
     *
     * @param inboxAssetId           the verification participant's permanent CCM inbox asset
     * @param api                    how the dataspace identifies a CCM API offer in a catalog
     * @param certificate            the sample certificate a managed run publishes
     * @param expectedEvents         a managed run's ledger checklist
     * @param externalExpectedEvents an external run's ledger checklist — deliberately short: the
     *                               identity events belong to wallets THIS environment provisions,
     *                               and the exchange's events carry the verification participant's
     *                               context rather than the SUT's
     */
    public record Ccm(
            String inboxAssetId,
            CcmApiVocabulary api,
            SampleCertificate certificate,
            Map<String, Integer> expectedEvents,
            Map<String, Integer> externalExpectedEvents) {

        public Ccm {
            expectedEvents = expectedEvents == null ? Map.of() : Map.copyOf(expectedEvents);
            externalExpectedEvents = externalExpectedEvents == null ? Map.of() : Map.copyOf(externalExpectedEvents);
        }
    }

    /**
     * The catalog vocabulary a dataspace's CCM standard identifies an API offer by (Catena-X:
     * CX-0135 — {@code dct:type} {@code <taxonomy>CCMAPI}, a {@code dct:subject} naming the
     * provider or consumer API, and a version property).
     *
     * @param taxonomy        the namespace of the API type and subjects
     * @param taxonomyPrefix  the prefix a compacted catalog may write the taxonomy as
     * @param versionProperty the full IRI of the version property
     * @param versionPrefix   the prefix a compacted catalog may write the version property's namespace as
     * @param version         the API version this environment offers and looks for
     */
    public record CcmApiVocabulary(
            String taxonomy,
            String taxonomyPrefix,
            String versionProperty,
            String versionPrefix,
            String version) {
    }

    /** The issuer and site of the sample certificate a managed run publishes. */
    public record SampleCertificate(String issuerName, String issuerId, String siteId) {
    }

    /** @throws NoSuchElementException for a dataspace this environment has no profile of */
    public DataspaceProfile dataspace(String id) {
        var profile = dataspaces.get(id);
        if (profile == null) {
            throw new NoSuchElementException("Dataspace '%s' is not configured".formatted(id));
        }
        return profile;
    }

    /** The provider's DSP endpoint for a participant context, as the consumer side dials it. */
    public String dspAddressOf(String providerPcid, String dspProfile) {
        return "%s/%s/%s".formatted(dspBaseUrl, providerPcid, dspProfile);
    }
}
