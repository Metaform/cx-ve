package com.metaform.cxve.hub.domain.model;

import java.util.Set;

/**
 * Lifecycle of a membership: the registration leg first (driven by the Onboarding API, whose
 * status callback records the outcome), then the post-confirmation leg — provisioning the
 * member's EDC resources through the CFM Tenant Manager when this environment hosts it, and then,
 * for EVERY member, offering it credentials. Both legs are triggered BY the CONFIRMED callback, so
 * the happy path is SUBMITTED → CONFIRMED → PROVISIONING → PROVISIONED → CREDENTIALS_OFFERED, with
 * the PROVISIONED step skipped for a member that brought its own participant resources. Each step
 * is taken by whichever thread carries the triggering signal — which is why transitions are
 * MONOTONIC: {@link #canAdvanceTo} is the single transition table, and a late or redelivered
 * signal that would move a record backwards is ignored instead of applied.
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

    /**
     * The post-confirmation work is claimed and running: the participant profile is being
     * deployed, or — for a member whose resources live elsewhere — the credential offer is being
     * sent. Entering it is the at-most-once gate for that work.
     */
    PROVISIONING,

    /**
     * The participant context exists — the member's EDC resources are provisioned. NOT terminal:
     * the credential offer still follows, so a caller waiting for a usable member waits for
     * CREDENTIALS_OFFERED.
     */
    PROVISIONED,

    /**
     * Terminal success: the IssuerService accepted the credential offer for delivery to the
     * member's Credential Service. Whether the member then requests and receives the credentials
     * is observable on the issuance events, not on this record.
     */
    CREDENTIALS_OFFERED,

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
            // PROVISIONED is skipped by a member this environment does not host: it has no
            // profile to deploy, so its claim goes straight to the credential offer
            case PROVISIONING -> Set.of(PROVISIONED, CREDENTIALS_OFFERED, FAILED).contains(next);
            case PROVISIONED -> next == CREDENTIALS_OFFERED || next == FAILED;
            case CREDENTIALS_OFFERED, REJECTED, FAILED -> false;
        };
    }
}
