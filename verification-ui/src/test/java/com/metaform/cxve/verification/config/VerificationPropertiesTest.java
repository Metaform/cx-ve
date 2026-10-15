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
 * participant contexts). A clash fails at startup rather than at the second participant's ensure.
 */
class VerificationPropertiesTest {

    private static final VerificationProperties BASE = TestFixtureAccess.props(Map.of());
    private static final VerificationProperties.DataspaceProfile CATENA_X = BASE.dataspace(TestFixtureAccess.DATASPACE);

    private static VerificationProperties.DataspaceProfile copy(String shortName, String inboxAssetId) {
        var ccm = CATENA_X.useCase("ccm");
        var useCase = new VerificationProperties.UseCase(ccm.displayName(), ccm.enabled(),
                new VerificationProperties.Ccm(inboxAssetId, ccm.ccm().api(), ccm.ccm().certificate(),
                        ccm.ccm().expectedEvents(), ccm.ccm().externalExpectedEvents()));
        return new VerificationProperties.DataspaceProfile("Other", CATENA_X.memberId(), CATENA_X.dspProfile(),
                CATENA_X.policyContext(), CATENA_X.accessConstraints(), CATENA_X.contractConstraints(),
                CATENA_X.registrationTemplate(),
                new VerificationProperties.ParticipantIdentity("Other VP", shortName, "OTHER-1", "OTHERVERIFY1"),
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
                .hasMessageContaining("catena-x")
                .hasMessageContaining("other-x");
    }

    @Test
    void aSharedShortNameIsRefused() {
        assertThatThrownBy(() -> with(copy("verification-participant", "ccm-inbox-other")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("short-name 'verification-participant'");
    }
}
