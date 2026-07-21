package com.beardyinc.cxve.domain.model;

/**
 * Tracks a single partner onboarding as it moves through {@link OnboardingState}.
 * {@code externalId} is the caller-supplied correlation id from the registration payload;
 * {@code bpn}, {@code participantProfileId} and {@code holderId} are populated as the corresponding steps
 * complete. {@code holderId} is the identifier used to correlate downstream IdentityHub issuance
 * events back to this process.
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


    public static OnboardingProcess submitted(String id, String externalId) {
        return new OnboardingProcess(id, externalId, OnboardingState.SUBMITTED, null, null, null, null);
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
