package com.metaform.cxve.hub.e2e;

/**
 * The suite in DECADE-X: members are onboarded through the dx-onboarding-api (the TSP onboarding
 * intake, approved automatically), dialled under the {@code decade-x} DSP profile, with
 * constraint-free offers.
 */
class DecadeXE2eTest extends VerificationEnvironmentE2eTest {

    DecadeXE2eTest() {
        super(E2eDataspace.DECADE_X);
    }
}
