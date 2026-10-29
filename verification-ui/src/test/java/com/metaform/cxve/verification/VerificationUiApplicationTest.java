package com.metaform.cxve.verification;

import com.metaform.cxve.verification.config.VerificationProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context smoke test: the full application context (incl. the {@code verification.*} properties
 * binding — durations, nested records, the dataspace profiles, the bracketed-key expected-events maps) must assemble from
 * the packaged defaults alone.
 */
@SpringBootTest
class VerificationUiApplicationTest {

    @Autowired
    private VerificationProperties properties;

    @Test
    void contextLoadsAndPropertiesBind() {
        // DECADE-X is the only dataspace configured
        assertEquals(1, properties.dataspaces().size());
        var decadeX = properties.dataspace("decade-x");
        assertEquals("decade-x", decadeX.dspProfile());
        // DECADE-X assigns member ids: its verification participant declares none
        assertTrue(decadeX.memberId().assigned());
        assertNull(decadeX.verificationParticipant().memberId());
        var ccm = decadeX.useCase("ccm").ccm();
        assertEquals("ccm-inbox-verification-dx", ccm.inboxAssetId());
        assertEquals("https://w3id.org/dsaf/sce/v1/provider-api", ccm.api().providerApi());
        assertEquals("sce-1.0", ccm.protocol());
        assertEquals("iso9001", ccm.certificateType());
        assertEquals(2, ccm.expectedEvents().get("events.contract.negotiation.finalized"));
        assertEquals("Company Certificate Management", decadeX.useCase("ccm").displayName());
        assertEquals("Substance tracing", decadeX.useCase("substance-tracing").displayName());
        assertFalse(decadeX.useCase("substance-tracing").enabled());
        // a dataspace may leave its policies unset
        assertEquals(0, decadeX.accessConstraints().size());
        // DECADE-X's checklist: its own onboarding events and its credential's delivery
        assertEquals(Map.of("events.onboarding.started", 1, "events.onboarding.completed", 1,
                        "events.issuance.credential.delivered", 1),
                ccm.externalExpectedEvents());
        assertFalse(properties.timeouts().onboarding().isZero());
    }
}
