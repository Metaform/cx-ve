package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.config.VerificationProperties.PolicyConstraint;
import com.metaform.cxve.verification.domain.model.Membership;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Shared builders for the application-layer tests: tight timeouts and one dataspace — Catena-X,
 * with the CCM use case and its fixed verification participant.
 */
final class TestFixtures {

    static final String DATASPACE = "catena-x";
    static final String USE_CASE = "ccm";
    static final String DSP_PROFILE = "cx-neptune";
    static final String POLICY_CONTEXT = "https://w3id.org/catenax/2025/9/policy/context.jsonld";
    static final List<PolicyConstraint> ACCESS = List.of(new PolicyConstraint("Membership", "eq", "active"));
    static final List<PolicyConstraint> CONTRACT = List.of(
            new PolicyConstraint("FrameworkAgreement", "eq", "DataExchangeGovernance:1.0"),
            new PolicyConstraint("UsagePurpose", "isAnyOf", "cx.pcf.base:1"));
    static final VerificationProperties.CcmApiVocabulary CCM_API = new VerificationProperties.CcmApiVocabulary(
            "https://w3id.org/catenax/taxonomy#", "cx-taxo",
            "https://w3id.org/catenax/ontology/common#version", "cx-common", "3.0");
    static final VerificationProperties.SampleCertificate CERTIFICATE =
            new VerificationProperties.SampleCertificate("Test CA", "BPNL00000000ISSUER", "BPNA00000000MAIN0");

    private TestFixtures() {
    }

    static VerificationProperties props(Map<String, Integer> expectedEvents) {
        var tight = Duration.ofMillis(300);
        var ccm = new VerificationProperties.Ccm("ccm-inbox-verification", CCM_API, CERTIFICATE, expectedEvents,
                Map.of("events.issuance.credential.delivered", 1));
        var catenaX = new VerificationProperties.DataspaceProfile(
                "Catena-X",
                new VerificationProperties.MemberId("BPN", "BPNL[0-9A-Z]{12}", "BPNL000000000001"),
                DSP_PROFILE, POLICY_CONTEXT, ACCESS, CONTRACT,
                """
                {"city": "Munich", "uniqueIds": [ { "type": "VAT_ID", "value": "{{uniqueId}}" } ]}""",
                new VerificationProperties.ParticipantIdentity(
                        "Verification Participant", "verification-participant", "BPNLVERIFY000001", "DEVERIFY0001"),
                Map.of(USE_CASE, new VerificationProperties.UseCase("Company Certificate Management", true, ccm),
                        "traceability", new VerificationProperties.UseCase("Traceability", false, null)));
        return new VerificationProperties(
                "http://gw/api/dsp",
                new VerificationProperties.TokenSpec("issuer", "admin"),
                new VerificationProperties.TokenSpec("sudo", "certo-mgmt-api:write"),
                "https://w3id.org/dspace-sig/profile/http-pull",
                "events.issuance.credential.delivered",
                new VerificationProperties.Timeouts(tight, tight, tight, tight, tight, tight),
                Duration.ofMillis(10),
                new VerificationProperties.External("http", tight, tight, tight),
                Map.of(DATASPACE, catenaX));
    }

    static Membership membership(String externalId, String state, String did, String pcid, String processId) {
        return new Membership(externalId, DATASPACE, "Some Participant", did, "BPNLPUT000000001", state,
                processId, "tenant-1", "profile-1", pcid, null);
    }

    /** A membership of a participant hosted elsewhere: its own DID, nothing provisioned here. */
    static Membership externalMembership(String externalId, String state, String did, String processId) {
        return new Membership(externalId, DATASPACE, "Some SUT", did, "BPNLPUT000000001", state,
                processId, null, null, null, null);
    }
}
