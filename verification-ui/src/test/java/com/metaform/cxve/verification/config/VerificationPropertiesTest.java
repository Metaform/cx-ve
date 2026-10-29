package com.metaform.cxve.verification.config;

import com.metaform.cxve.verification.application.TestFixtureAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two dataspaces' verification participants are separate participants: they must not share the
 * short name (it forms the hosted DID) or the inbox asset id (control-plane ids are unique across
 * participant contexts). A clash fails at startup rather than at the second participant's ensure —
 * and so does a declared member id in a dataspace that assigns them.
 */
class VerificationPropertiesTest {

    private static final VerificationProperties BASE = TestFixtureAccess.props(Map.of());
    private static final VerificationProperties.DataspaceProfile DECLARING = BASE.dataspace(TestFixtureAccess.DATASPACE);

    private static VerificationProperties.DataspaceProfile copy(String shortName, String inboxAssetId) {
        return copy(shortName, inboxAssetId, DECLARING.memberId(), "OTHER-1");
    }

    private static VerificationProperties.DataspaceProfile copy(String shortName, String inboxAssetId,
                                                                VerificationProperties.MemberId format,
                                                                String participantMemberId) {
        var ccm = DECLARING.useCase("ccm");
        var useCase = new VerificationProperties.UseCase(ccm.displayName(), ccm.enabled(),
                new VerificationProperties.Ccm(inboxAssetId, ccm.ccm().api(), ccm.ccm().protocol(),
                        ccm.ccm().certificateType(), ccm.ccm().certificate(),
                        ccm.ccm().expectedEvents(), ccm.ccm().externalExpectedEvents()));
        return new VerificationProperties.DataspaceProfile("Other", format, DECLARING.dspProfile(),
                DECLARING.policyContext(), DECLARING.accessConstraints(), DECLARING.contractConstraints(),
                DECLARING.registrationTemplate(),
                new VerificationProperties.ParticipantIdentity("Other VP", shortName, participantMemberId, "OTHERVERIFY1"),
                Map.of("ccm", useCase));
    }

    private static VerificationProperties with(VerificationProperties.DataspaceProfile other) {
        var dataspaces = new LinkedHashMap<>(BASE.dataspaces());
        dataspaces.put("other-x", other);
        return new VerificationProperties(BASE.dspBaseUrl(), BASE.management(), BASE.certoAuth(), BASE.transferType(),
                BASE.credentialDeliverySubject(), BASE.timeouts(), BASE.pollInterval(), BASE.external(), dataspaces);
    }

    @Test
    void distinctParticipantsAreAccepted() {
        assertThatCode(() -> with(copy("verification-participant-other", "ccm-inbox-other"))).doesNotThrowAnyException();
    }

    @Test
    void aSharedInboxAssetIdIsRefused() {
        assertThatThrownBy(() -> with(copy("verification-participant-other", "ccm-inbox-verification")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inbox-asset-id 'ccm-inbox-verification'")
                .hasMessageContaining("example-x")
                .hasMessageContaining("other-x");
    }

    @Test
    void aSharedShortNameIsRefused() {
        assertThatThrownBy(() -> with(copy("verification-participant", "ccm-inbox-other")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("short-name 'verification-participant'");
    }

    @Test
    void aDeclaredParticipantMemberIdIsRefusedWhereTheDataspaceAssignsThem() {
        var assigning = new VerificationProperties.MemberId("Other-ID", "", "O-1", true);

        assertThatCode(() -> with(copy("verification-participant-other", "ccm-inbox-other", assigning, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> with(copy("verification-participant-other", "ccm-inbox-other", assigning, "OTHER-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verification.dataspaces.other-x")
                .hasMessageContaining("leave verification-participant.member-id out");
    }
}
