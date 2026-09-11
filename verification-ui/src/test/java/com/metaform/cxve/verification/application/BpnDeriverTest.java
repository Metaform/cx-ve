package com.metaform.cxve.verification.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact derivations (values produced by the e2e suite's original formula) so any
 * drift in the formula — including in the FRONTEND's mirror (frontend/src/app/core/bpn.ts),
 * which previews the same values client-side — is caught against these literals.
 */
class BpnDeriverTest {

    @Test
    void bpnFor_matchesTheE2eDerivation() {
        assertEquals("BPNL40C1797F0000", BpnDeriver.bpnFor("verification-participant"));
        assertEquals("BPNL73B2D2FE0000", BpnDeriver.bpnFor("put-1a2b3c4d"));
        assertEquals("BPNL190C6D7A0000", BpnDeriver.bpnFor("provider-abc"));
    }

    @Test
    void vatIdFor_matchesTheE2eDerivation() {
        assertEquals("DE40C1797F", BpnDeriver.vatIdFor("verification-participant"));
        assertEquals("DE190C6D7A", BpnDeriver.vatIdFor("provider-abc"));
    }

    @Test
    void bpnFor_isAlways16Characters() {
        for (var seed : new String[] { "", "a", "polsky", "some-very-long-short-name-indeed" }) {
            assertEquals(16, BpnDeriver.bpnFor(seed).length());
        }
    }
}
