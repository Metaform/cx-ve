package com.beardyinc.cxve.onboarding;

/**
 * Tracks a single partner onboarding as it moves through {@link OnboardingState}.
 * {@code externalId} is the caller-supplied correlation id from the registration payload;
 * {@code bpn} and {@code walletId} are populated as the corresponding steps complete.
 *
 * <p>Immutable — each transition returns a new instance via the {@code with*} helpers.
 */
public record OnboardingProcess(
        String id,
        String externalId,
        OnboardingState state,
        String bpn,
        String walletId,
        String failureReason
) {

    public static OnboardingProcess submitted(String id, String externalId) {
        return new OnboardingProcess(id, externalId, OnboardingState.SUBMITTED, null, null, null);
    }

    public OnboardingProcess withState(OnboardingState newState) {
        return new OnboardingProcess(id, externalId, newState, bpn, walletId, failureReason);
    }

    public OnboardingProcess withBpn(String assignedBpn) {
        return new OnboardingProcess(id, externalId, OnboardingState.BPN_ASSIGNED, assignedBpn, walletId, failureReason);
    }

    public OnboardingProcess withWallet(String provisionedWalletId) {
        return new OnboardingProcess(id, externalId, OnboardingState.WALLET_PROVISIONED, bpn, provisionedWalletId, failureReason);
    }

    public OnboardingProcess rejected(String reason) {
        return new OnboardingProcess(id, externalId, OnboardingState.REJECTED, bpn, walletId, reason);
    }

    public OnboardingProcess failed(String reason) {
        return new OnboardingProcess(id, externalId, OnboardingState.FAILED, bpn, walletId, reason);
    }

    public boolean isTerminal() {
        return state == OnboardingState.COMPLETED
                || state == OnboardingState.REJECTED
                || state == OnboardingState.FAILED;
    }
}
