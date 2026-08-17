package com.metaform.cxve.domain.model;

/**
 * Tracks a single partner onboarding as it moves through {@link OnboardingState}.
 * {@code externalId} is the caller-supplied correlation id from the registration payload.
 *
 * <p>{@code bpn} and {@code holderId} are AUTHORITATIVE on this record and seeded at submission:
 * the BPN with the payload's (mandatory) value, the holder id with the participant DID resolved by
 * the same rule provisioning will use. The corresponding steps later confirm or overwrite them
 * ({@code withBpn} with the resolved/created BPN, {@code withHolderId} with the provisioned
 * participant's identifier) — queries and events read them from here, never from the payload.
 * {@code holderId} is also the identifier that correlates downstream IdentityHub issuance events
 * back to this process.
 *
 * <p>Immutable — each transition returns a new instance via the {@code with*} helpers.
 */
public record OnboardingProcess(
        String id,
        String externalId,
        OnboardingState state,
        String bpn,
        String participantProfileId,
        String holderId,
        String failureReason,
        String holderProcessId,
        String tenantId,
        String participantContextId
) {


    public OnboardingProcess(String id, String externalId, OnboardingState state, String bpn, String participantProfileId, String holderId, String failureReason) {
        this(id, externalId, state, bpn, participantProfileId, holderId, failureReason, null, null, null);
    }


    public static OnboardingProcess submitted(String id, String externalId, String bpn, String did) {
        return new OnboardingProcess(id, externalId, OnboardingState.SUBMITTED, bpn, null, did, null);
    }

    public OnboardingProcess withState(OnboardingState newState) {
        return new OnboardingProcess(id, externalId, newState, bpn, participantProfileId, holderId, failureReason, holderProcessId, tenantId, participantContextId);
    }

    public OnboardingProcess withBpn(String assignedBpn) {
        return new OnboardingProcess(id, externalId, OnboardingState.BPN_ASSIGNED, assignedBpn, participantProfileId, holderId, failureReason, holderProcessId, tenantId, participantContextId);
    }

    public OnboardingProcess withParticipantProfile(String provisionedWalletId) {
        return new OnboardingProcess(id, externalId, state, bpn, provisionedWalletId, holderId, failureReason, holderProcessId, tenantId, participantContextId);
    }

    public OnboardingProcess withHolderId(String assignedHolderId) {
        return new OnboardingProcess(id, externalId, state, bpn, participantProfileId, assignedHolderId, failureReason, holderProcessId, tenantId, participantContextId);
    }

    public OnboardingProcess rejected(String reason) {
        return new OnboardingProcess(id, externalId, OnboardingState.REJECTED, bpn, participantProfileId, holderId, reason, holderProcessId, tenantId, participantContextId);
    }

    public OnboardingProcess failed(String reason) {
        return new OnboardingProcess(id, externalId, OnboardingState.FAILED, bpn, participantProfileId, holderId, reason, holderProcessId, tenantId, participantContextId);
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

    public OnboardingProcess withHolderProcessId(String holderProcessId) {
        return new OnboardingProcess(id, externalId, OnboardingState.WALLET_PROVISIONED, bpn, participantProfileId, holderId, failureReason, holderProcessId, tenantId, participantContextId);
    }

    public OnboardingProcess withTenantId(String tenantId) {
        return new OnboardingProcess(id, externalId, state, bpn, participantProfileId, holderId, failureReason, holderProcessId, tenantId, participantContextId);
    }

    public OnboardingProcess withParticipantContextId(String participantContextId) {
        return new OnboardingProcess(id, externalId, state, bpn, participantProfileId, holderId, failureReason, holderProcessId, tenantId, participantContextId);
    }


}
