package com.metaform.cxve.domain.model;

/**
 * Tracks a single partner onboarding as it moves through {@link OnboardingState}.
 * {@code externalId} is the caller-supplied correlation id from the registration payload.
 *
 * <p>{@code bpn} and {@code holderId} are AUTHORITATIVE on this record and seeded at submission:
 * the BPN with the payload's value (null when the registration did not supply one), the holder id
 * with the participant DID resolved by the same rule the holder registration will use. The
 * corresponding steps later confirm or assign them ({@code withBpn} with the resolved/created
 * BPN) — queries and events read them from here, never from the payload.
 * {@code holderId} is also the id the participant is registered under as a credential holder with
 * the IssuerService.
 *
 * <p>{@code clientId} is the authenticated OSP client that submitted the registration, seeded at
 * submission from the caller's token — never from the payload. It is what status callbacks are
 * routed by; null only for processes predating authentication (which fall back to callback
 * fan-out).
 *
 * <p>Immutable — each transition returns a new instance via the {@code with*} helpers.
 */
public record OnboardingProcess(
        String id,
        String externalId,
        OnboardingState state,
        String bpn,
        String holderId,
        String failureReason,
        String clientId
) {

    public static OnboardingProcess submitted(String id, String externalId, String bpn, String did, String clientId) {
        return new OnboardingProcess(id, externalId, OnboardingState.SUBMITTED, bpn, did, null, clientId);
    }

    /** A submission without a recorded submitting client — tests and pre-authentication data. */
    public static OnboardingProcess submitted(String id, String externalId, String bpn, String did) {
        return submitted(id, externalId, bpn, did, null);
    }

    public OnboardingProcess withState(OnboardingState newState) {
        return new OnboardingProcess(id, externalId, newState, bpn, holderId, failureReason, clientId);
    }

    public OnboardingProcess withBpn(String assignedBpn) {
        return new OnboardingProcess(id, externalId, OnboardingState.BPN_ASSIGNED, assignedBpn, holderId, failureReason, clientId);
    }

    public OnboardingProcess withHolderId(String assignedHolderId) {
        return new OnboardingProcess(id, externalId, state, bpn, assignedHolderId, failureReason, clientId);
    }

    public OnboardingProcess rejected(String reason) {
        return new OnboardingProcess(id, externalId, OnboardingState.REJECTED, bpn, holderId, reason, clientId);
    }

    public OnboardingProcess failed(String reason) {
        return new OnboardingProcess(id, externalId, OnboardingState.FAILED, bpn, holderId, reason, clientId);
    }

    public boolean isTerminal() {
        return state == OnboardingState.COMPLETED
                || state == OnboardingState.REJECTED
                || state == OnboardingState.FAILED;
    }

    /**
     * True once validation has passed and the process has not been rejected or failed — i.e. this
     * registration (in flight or completed) blocks duplicate registrations.
     */
    public boolean isActiveRegistration() {
        return state != OnboardingState.SUBMITTED
                && state != OnboardingState.REJECTED
                && state != OnboardingState.FAILED;
    }
}
