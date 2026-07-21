package com.beardyinc.cxve.application;

import com.beardyinc.cxve.domain.model.OnboardingProcess;
import com.beardyinc.cxve.domain.model.OnboardingState;
import com.beardyinc.cxve.domain.model.PartnerRegistrationData;
import java.util.Optional;

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

    /**
     * Records the {@code holderId} that downstream IdentityHub issuance events will carry for this
     * process, so those events can later be correlated back to it. Set once the wallet/participant
     * profile has been provisioned and the holder identity is known.
     */
    void linkHolder(String processId, String holderId);

    /**
     * Resumes the process correlated to {@code holderId} — invoked when an issuance event arrives
     * from NATS. Idempotent: a redelivered event for an already-terminal process is a no-op.
     *
     * @return the resulting process, or empty if no process is linked to that holder.
     */
    Optional<OnboardingProcess> advanceByHolder(String holderId);
}
