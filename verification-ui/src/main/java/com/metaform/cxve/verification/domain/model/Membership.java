package com.metaform.cxve.verification.domain.model;

/**
 * Local mirror of the Membership Hub's membership record — the correlated view of a member's
 * registration and provisioning. Mirrored on purpose (like the e2e suite's wire shapes): this
 * app consumes the hub's HTTP contract exactly as an external client would, without a code
 * dependency on the hub module.
 */
public record Membership(
        String externalId,
        String name,
        String did,
        String bpn,
        String state,
        String onboardingProcessId,
        String tenantId,
        String participantProfileId,
        String participantContextId,
        String failureReason) {

    /**
     * Everything a run needs from this record: the participant context (its resources exist) and
     * the onboarding process id (its registration is with the Onboarding API — the event ledger is
     * keyed by it). Both are present from the moment the hub submits the registration, which it
     * does only once the deployment has completed.
     */
    public boolean hasParticipantResources() {
        return participantContextId != null && !participantContextId.isBlank()
                && onboardingProcessId != null && !onboardingProcessId.isBlank();
    }

    /**
     * The terminal success of every member: its registration was confirmed, which means the
     * Onboarding API registered it as a credential holder and had the issuer offer it the
     * membership credentials. Whether the member then requested and received them is a separate
     * question, answered by the event ledger.
     */
    public boolean isCredentialsOffered() {
        return "CREDENTIALS_OFFERED".equals(state);
    }

    /** Terminal failures and never-provisioned registrations — polling them is pointless. */
    public boolean isDeadEnd() {
        return "REJECTED".equals(state) || "FAILED".equals(state) || "REGISTERING".equals(state);
    }

    /** Usable for a run: not a dead end, whatever stage it has reached. */
    public boolean isReusable() {
        return !isDeadEnd();
    }
}
