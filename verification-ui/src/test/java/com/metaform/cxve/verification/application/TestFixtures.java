package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.Membership;
import java.time.Duration;
import java.util.Map;

/** Shared builders for the application-layer tests: tight timeouts, the fixed VP identity. */
final class TestFixtures {

    private TestFixtures() {
    }

    static VerificationProperties props(Map<String, Integer> expectedEvents) {
        var tight = Duration.ofMillis(300);
        return new VerificationProperties(
                "http://gw/api/dsp",
                "http://certo-svc:8080",
                new VerificationProperties.TokenSpec("issuer", "admin"),
                new VerificationProperties.TokenSpec("sudo", "certo-mgmt-api:write"),
                new VerificationProperties.ParticipantIdentity(
                        "Verification Participant", "verification-participant", "BPNLVERIFY000001", "DEVERIFY0001"),
                "ccm-inbox-verification",
                "HttpData-PULL",
                new VerificationProperties.Timeouts(tight, tight, tight, tight, tight, tight),
                Duration.ofMillis(10),
                expectedEvents,
                new VerificationProperties.External("ccm-api", "http", tight, tight, tight,
                        Map.of("events.issuance.credential.delivered", 1)));
    }

    static Membership membership(String externalId, String state, String did, String pcid, String processId) {
        return new Membership(externalId, "Some Participant", did, "BPNLPUT000000001", state,
                processId, "tenant-1", "profile-1", pcid, null, false);
    }

    /** A membership of a participant hosted elsewhere: its own DID, nothing provisioned here. */
    static Membership externalMembership(String externalId, String state, String did, String processId) {
        return new Membership(externalId, "Some SUT", did, "BPNLPUT000000001", state,
                processId, null, null, null, null, true);
    }
}
