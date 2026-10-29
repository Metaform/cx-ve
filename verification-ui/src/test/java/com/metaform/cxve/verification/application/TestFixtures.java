package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.config.VerificationProperties.PolicyConstraint;
import com.metaform.cxve.verification.domain.model.Membership;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Shared builders for the application-layer tests: tight timeouts and one dataspace — a made-up
 * one whose members declare their ids, with constrained policies, the CCM use case and a fixed
 * verification participant. {@link #assigningProps} has a dataspace that assigns its members' ids
 * instead, as DECADE-X does.
 */
final class TestFixtures {

    static final String DATASPACE = "example-x";
    static final String USE_CASE = "ccm";
    static final String DSP_PROFILE = "example-profile";
    static final String POLICY_CONTEXT = "https://example.com/policy/context.jsonld";
    static final List<PolicyConstraint> ACCESS = List.of(new PolicyConstraint("Membership", "eq", "active"));
    static final List<PolicyConstraint> CONTRACT = List.of(
            new PolicyConstraint("FrameworkAgreement", "eq", "Example:1.0"),
            new PolicyConstraint("UsagePurpose", "isAnyOf", "example.purpose:1"));
    static final VerificationProperties.CcmApiVocabulary CCM_API = new VerificationProperties.CcmApiVocabulary(
            "https://w3id.org/dsaf/sce/v1/provider-api", "https://w3id.org/dsaf/sce/v1/consumer-api");
    static final String PROTOCOL = "sce-1.0";
    static final String CERTIFICATE_TYPE = "iso9001";
    static final VerificationProperties.SampleCertificate CERTIFICATE =
            new VerificationProperties.SampleCertificate("Test CA", "EX-00000000");
    static final String ASSIGNING_DATASPACE = "decade-x";

    private TestFixtures() {
    }

    static VerificationProperties props(Map<String, Integer> expectedEvents) {
        var ccm = new VerificationProperties.Ccm("ccm-inbox-verification", CCM_API, PROTOCOL, CERTIFICATE_TYPE, CERTIFICATE, expectedEvents,
                Map.of("events.issuance.credential.delivered", 1));
        var example = new VerificationProperties.DataspaceProfile(
                "Example-X",
                new VerificationProperties.MemberId("Member ID", "EX-[0-9]{8}", "EX-00000001", false),
                DSP_PROFILE, POLICY_CONTEXT, ACCESS, CONTRACT,
                """
                {"city": "Munich", "uniqueIds": [ { "type": "VAT_ID", "value": "{{uniqueId}}" } ]}""",
                new VerificationProperties.ParticipantIdentity(
                        "Verification Participant", "verification-participant", "EX-00000099", "DEVERIFY0001"),
                Map.of(USE_CASE, new VerificationProperties.UseCase("Company Certificate Management", true, ccm),
                        "traceability", new VerificationProperties.UseCase("Traceability", false, null)));
        return properties(Map.of(DATASPACE, example));
    }

    /**
     * One dataspace, {@link #ASSIGNING_DATASPACE}, that assigns its members' ids: its verification
     * participant declares none, and its registration template still names {@code {{memberId}}}.
     */
    static VerificationProperties assigningProps() {
        var ccm = new VerificationProperties.Ccm("ccm-inbox-verification-dx", CCM_API, PROTOCOL, CERTIFICATE_TYPE, CERTIFICATE, Map.of(),
                Map.of("events.issuance.credential.delivered", 1));
        var decadeX = new VerificationProperties.DataspaceProfile(
                "DECADE-X",
                new VerificationProperties.MemberId("DECADE-X-ID", "DX-[0-9]{8}", "DX-00000001", true),
                "dx-2025", POLICY_CONTEXT, List.of(), List.of(),
                """
                {"ref": "{{memberId}}", "uniqueIds": [ { "type": "vatID", "value": "{{uniqueId}}" } ]}""",
                new VerificationProperties.ParticipantIdentity(
                        "Verification Participant DX", "verification-participant-dx", null, "DEVERIFYDX01"),
                Map.of(USE_CASE, new VerificationProperties.UseCase("Company Certificate Management", true, ccm)));
        return properties(Map.of(ASSIGNING_DATASPACE, decadeX));
    }

    private static VerificationProperties properties(Map<String, VerificationProperties.DataspaceProfile> dataspaces) {
        var tight = Duration.ofMillis(300);
        return new VerificationProperties(
                "http://gw/api/dsp",
                new VerificationProperties.TokenSpec("issuer", "admin"),
                new VerificationProperties.TokenSpec("sudo", "certo-mgmt-api:write"),
                "https://w3id.org/dspace-sig/profile/http-pull",
                "events.issuance.credential.delivered",
                new VerificationProperties.Timeouts(tight, tight, tight, tight, tight, tight),
                Duration.ofMillis(10),
                new VerificationProperties.External("http", tight, tight, tight),
                dataspaces);
    }

    static Membership membership(String externalId, String state, String did, String pcid, String processId) {
        return new Membership(externalId, DATASPACE, "Some Participant", did, "EX-00000002", state,
                processId, "tenant-1", "profile-1", pcid, null);
    }

    /** A membership of a participant hosted elsewhere: its own DID, nothing provisioned here. */
    static Membership externalMembership(String externalId, String state, String did, String processId) {
        return new Membership(externalId, DATASPACE, "Some SUT", did, "EX-00000002", state,
                processId, null, null, null, null);
    }
}
