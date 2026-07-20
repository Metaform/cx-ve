package com.beardyinc.cxve.onboarding;

import com.beardyinc.cxve.model.PartnerRegistrationData;

/**
 * Drives a partner onboarding through the CX-0006 sequence. The POST to {@code /partnerRegistration}
 * only needs to call {@link #start}; the remaining steps are asynchronous (identity proofing and
 * credential issuance involve out-of-band systems), so the orchestrator persists an
 * {@link OnboardingProcess} and advances it as each step completes.
 *
 * <p>Step order per CX-0006:
 * validate → assign BPN → identity proofing → provision wallet →
 * issue BPN + Framework Agreement + Membership credentials → complete.
 */
public interface OnboardingOrchestrator {

    /**
     * Registers a new onboarding from submitted registration data and begins processing.
     *
     * @return the id of the created {@link OnboardingProcess}, in {@link OnboardingState#SUBMITTED}.
     */
    String start(PartnerRegistrationData registrationData);

    /**
     * Advances the process one step from its current state, if the preconditions for the next step
     * are met. Called on submission and again whenever an async step reports completion (e.g. an
     * identity-proofing callback).
     *
     * @return the process in its resulting state.
     */
    OnboardingProcess advance(String processId);

    OnboardingProcess get(String processId);
}
