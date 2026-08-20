package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;

/**
 * Drives a partner onboarding through the CX-0006 sequence. The POST to {@code /partnerRegistration}
 * only needs to call {@link #start}; identity proofing may involve an out-of-band system, so the
 * orchestrator persists an {@link OnboardingProcess} and advances it as each step completes.
 *
 * <p>Step order per CX-0006:
 * validate → assign BPN → identity proofing → register the credential holder → complete.
 * Completion is what the CONFIRMED status callback reports; the participant's EDC resources are
 * provisioned downstream of that confirmation, outside this process.
 */
public interface OnboardingOrchestrator {

    /**
     * Registers a new onboarding from submitted registration data and begins processing.
     *
     * @param clientId the authenticated client that submitted the registration; recorded on the
     *         process as the callback-routing target for its status updates.
     * @return the id of the created {@link OnboardingProcess}, in {@link OnboardingState#SUBMITTED}.
     */
    String start(String clientId, PartnerRegistrationData registrationData);

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
