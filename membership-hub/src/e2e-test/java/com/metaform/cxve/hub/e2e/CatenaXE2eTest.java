package com.metaform.cxve.hub.e2e;

/**
 * The suite in Catena-X: members are onboarded through the cx-onboarding-api (CX-0009 partner
 * registration, confirmed by status callback) under the BPN they declare, dialled under the
 * {@code cx-neptune} DSP profile, with offers that ask for the Catena-X credentials. The
 * certificate exchange is DSAF SCE on Certo, as in DECADE-X — not CX-0135.
 */
class CatenaXE2eTest extends VerificationEnvironmentE2eTest {

    CatenaXE2eTest() {
        super(E2eDataspace.CATENA_X);
    }
}
