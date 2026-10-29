package com.metaform.cxve.verification.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact derivation (values produced by the e2e suite's original formula) so any drift
 * in the formula is caught against these literals.
 */
class VatIdDeriverTest {

    @Test
    void vatIdFor_matchesTheE2eDerivation() {
        assertEquals("DE40C1797F", VatIdDeriver.vatIdFor("verification-participant"));
        assertEquals("DE190C6D7A", VatIdDeriver.vatIdFor("provider-abc"));
    }
}
