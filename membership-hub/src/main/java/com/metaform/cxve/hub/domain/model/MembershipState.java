package com.metaform.cxve.hub.domain.model;

import java.util.Set;

/**
 * Lifecycle of a membership, in the order the hub drives it: FIRST the member's EDC resources are
 * deployed through the CFM Tenant Manager (when this environment hosts them), THEN the registration
 * is submitted to the Onboarding API, which registers the credential holder and offers it the
 * dataspace's credentials before confirming. So the happy path is PROVISIONING → PROVISIONED →
 * SUBMITTED → CREDENTIALS_OFFERED, and a member that brought its own participant resources starts
 * at SUBMITTED.
 *
 * <p>Deploying BEFORE registering is what makes that possible: the issuer pushes the credential
 * offer to the Credential Service the member's DID document advertises, so the wallet has to exist
 * by the time the registration runs — which for a member hosted here means after its profile is
 * deployed.
 *
 * <p>Each step is taken by whichever thread carries the triggering signal — which is why
 * transitions are MONOTONIC: {@link #canAdvanceTo} is the single transition table, and a late or
 * redelivered signal that would move a record backwards is ignored instead of applied.
 */
public enum MembershipState {

    /** The member's EDC resources are being deployed; the registration follows. */
    PROVISIONING,

    /**
     * The participant context exists — the member's EDC resources are provisioned, so its wallet
     * can receive a credential offer. NOT terminal: the registration still has to be submitted.
     */
    PROVISIONED,

    /** The registration is with the Onboarding API and awaits its status callback. */
    SUBMITTED,

    /**
     * Legacy state of the former synchronous flow (a registration whose callback did not arrive
     * within the submitting call). No longer produced — kept so persisted rows and wire clients
     * stay readable, and still allowed to advance, so a late callback HEALS such a record
     * instead of stranding it.
     */
    REGISTERING,

    /**
     * Legacy state: under the previous order the hub recorded the confirmation and then sent the
     * credential offer itself. No longer produced — the Onboarding API confirms a registration
     * only once it has registered the holder AND sent the offer, so a confirmation now lands the
     * record directly on {@link #CREDENTIALS_OFFERED}. Kept readable, and still allowed to advance
     * so rows an older hub left here heal on a redelivered callback.
     */
    CONFIRMED,

    /**
     * Terminal success: the registration was confirmed, which means the Onboarding API registered
     * the member as a credential holder and had the IssuerService offer it the dataspace's
     * credentials. Whether the member then requests and receives them is observable on the
     * issuance events, not on this record.
     */
    CREDENTIALS_OFFERED,

    /** The registration was rejected by the Onboarding API. Terminal. */
    REJECTED,

    /** A step failed irrecoverably. Terminal. */
    FAILED;

    /**
     * The single transition table. Everything not listed — including every transition out of a
     * terminal state and every backwards move (a redelivered confirmation against an already
     * offered record, a DECLINED after one) — is not an advance and must be ignored by callers.
     */
    public boolean canAdvanceTo(MembershipState next) {
        return switch (this) {
            case PROVISIONING -> next == PROVISIONED || next == FAILED;
            case PROVISIONED -> next == SUBMITTED || next == FAILED;
            // A confirmation is the terminal signal now, so SUBMITTED reaches CREDENTIALS_OFFERED
            // in one step; the legacy states are allowed the same move so old rows still heal.
            case SUBMITTED, REGISTERING -> Set.of(CREDENTIALS_OFFERED, REJECTED, FAILED).contains(next);
            case CONFIRMED -> next == CREDENTIALS_OFFERED || next == FAILED;
            case CREDENTIALS_OFFERED, REJECTED, FAILED -> false;
        };
    }
}
