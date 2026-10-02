package com.metaform.cxve.verification;

import com.metaform.cxve.verification.config.VerificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        var catenaX = properties.dataspace("catena-x");
        assertEquals("BPNLVERIFY000001", catenaX.verificationParticipant().memberId());
        assertEquals("cx-neptune", catenaX.dspProfile());
        assertEquals(3, catenaX.contractConstraints().size());
        var ccm = catenaX.useCase("ccm").ccm();
        assertEquals("ccm-inbox-verification", ccm.inboxAssetId());
        assertEquals("3.0", ccm.api().version());
        assertEquals(2, ccm.expectedEvents().get("events.contract.negotiation.finalized"));
        assertEquals("Company Certificate Management", catenaX.useCase("ccm").displayName());
        assertEquals("Traceability", catenaX.useCase("traceability").displayName());
        assertFalse(catenaX.useCase("traceability").enabled());
        var decadeX = properties.dataspace("decade-x");
        assertEquals("Company Certificate Management", decadeX.useCase("ccm").displayName());
        assertEquals("Substance tracing", decadeX.useCase("substance-tracing").displayName());
        assertFalse(decadeX.useCase("substance-tracing").enabled());
        // a dataspace may leave its policies and an external checklist unset
        assertEquals(0, properties.dataspace("decade-x").accessConstraints().size());
        assertEquals(0, properties.dataspace("decade-x").useCase("ccm").ccm().externalExpectedEvents().size());
        // Decade-X assigns an external participant's id
        assertTrue(properties.dataspace("decade-x").memberId().assignedToExternal());
        assertFalse(properties.dataspace("catena-x").memberId().assignedToExternal());
        assertFalse(properties.timeouts().onboarding().isZero());
    }
}
