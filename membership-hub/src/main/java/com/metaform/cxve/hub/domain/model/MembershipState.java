package com.metaform.cxve.hub.domain.model;

/**
 * Lifecycle of a membership: the registration leg first (driven by the Onboarding API and its
 * status callback), then the provisioning leg (driven by the CFM Tenant Manager).
 */
public enum MembershipState {

    /** Membership request received; the registration has not been submitted yet. */
    SUBMITTED,

    /** Registration submitted to the Onboarding API; awaiting its status callback. */
    REGISTERING,

    /** Registration CONFIRMED and the participant profile deployed; EDC resources are coming up. */
    PROVISIONING,

    /** The participant context exists — the member is fully provisioned. Terminal. */
    PROVISIONED,

    /** The registration was rejected by the Onboarding API. Terminal. */
    REJECTED,

    /** A step failed irrecoverably. Terminal. */
    FAILED
}
