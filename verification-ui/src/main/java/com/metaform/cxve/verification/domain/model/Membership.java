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

    public boolean isProvisioned() {
        return "PROVISIONED".equals(state);
    }

    /** Terminal failures and never-provisioned registrations — polling them is pointless. */
    public boolean isDeadEnd() {
        return "REJECTED".equals(state) || "FAILED".equals(state) || "REGISTERING".equals(state);
    }
}
