package com.metaform.cxve.hub.domain.model;

import java.util.Set;

/**
 * Lifecycle of a membership: the registration leg first (driven by the Onboarding API, whose
 * status callback records the outcome), then the provisioning leg (triggered BY the CONFIRMED
 * callback and driven by the CFM Tenant Manager). The happy path is SUBMITTED → CONFIRMED →
 * PROVISIONING → PROVISIONED, each step taken by whichever thread carries the triggering signal
 * — which is why transitions are MONOTONIC: {@link #canAdvanceTo} is the single transition
 * table, and a late or redelivered signal that would move a record backwards is ignored instead
 * of applied.
 */
public enum MembershipState {

    /** Membership request received; the registration is submitted and awaits its callback. */
    SUBMITTED,

    /**
     * Legacy state of the former synchronous flow (a registration whose callback did not arrive
     * within the submitting call). No longer produced — kept so persisted rows and wire clients
     * stay readable, and still allowed to advance, so a late callback HEALS such a record
     * instead of stranding it.
     */
    REGISTERING,

    /** Registration CONFIRMED by the Onboarding API's status callback; provisioning is next. */
    CONFIRMED,

    /** The participant profile is deployed or being deployed; EDC resources are coming up. */
    PROVISIONING,

    /** The participant context exists — the member is fully provisioned. Terminal. */
    PROVISIONED,

    /** The registration was rejected by the Onboarding API. Terminal. */
    REJECTED,

    /** A step failed irrecoverably. Terminal. */
    FAILED;

    /**
     * The single transition table. Everything not listed — including every transition out of a
     * terminal state and every backwards move (a redelivered CONFIRMED against a PROVISIONING
     * record, a DECLINED after confirmation) — is not an advance and must be ignored by callers.
     */
    public boolean canAdvanceTo(MembershipState next) {
        return switch (this) {
            case SUBMITTED, REGISTERING -> Set.of(CONFIRMED, REJECTED, FAILED).contains(next);
            case CONFIRMED -> next == PROVISIONING || next == FAILED;
            case PROVISIONING -> next == PROVISIONED || next == FAILED;
            case PROVISIONED, REJECTED, FAILED -> false;
        };
    }
}
