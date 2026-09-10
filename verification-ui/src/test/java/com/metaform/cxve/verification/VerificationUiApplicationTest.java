package com.metaform.cxve.verification;

import com.metaform.cxve.verification.config.VerificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Context smoke test: the full application context (incl. the {@code verification.*} properties
 * binding — durations, nested records, the bracketed-key expected-events map) must assemble from
 * the packaged defaults alone.
 */
@SpringBootTest
class VerificationUiApplicationTest {

    @Autowired
    private VerificationProperties properties;

    @Test
    void contextLoadsAndPropertiesBind() {
        assertEquals("BPNLVERIFY000001", properties.participant().bpn());
        assertEquals("ccm-inbox-verification", properties.inboxAssetId());
        assertEquals(2, properties.expectedEvents().get("events.contract.negotiation.finalized"));
        assertFalse(properties.timeouts().onboarding().isZero());
    }
}
