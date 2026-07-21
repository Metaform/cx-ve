package com.beardyinc.cxve.domain.port;

import com.beardyinc.cxve.domain.model.OnboardingProcess;

/**
 * CX-0006 identity proofing: verifies that the prospective participant is who they claim to be.
 * Modelled as an initiate/await pair since proofing is typically an out-of-band, asynchronous flow.
 */
public interface IdentityProofingService {

    /**
     * Kicks off identity proofing for the onboarding.
     *
     * @return a provider-specific reference used to correlate the later result.
     */
    String initiateProofing(OnboardingProcess process);

    /**
     * @return {@code true} once proofing for {@code proofingReference} has succeeded.
     */
    boolean isVerified(String proofingReference);
}
