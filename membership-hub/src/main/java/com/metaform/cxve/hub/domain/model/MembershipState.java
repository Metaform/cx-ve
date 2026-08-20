package com.metaform.cxve.hub.domain.model;

/**
 * Lifecycle of a membership: the registration leg first (driven by the Onboarding API, whose
 * status callback records the outcome), then the provisioning leg (driven by the CFM Tenant
 * Manager). The happy path runs through within the submitting call: SUBMITTED → CONFIRMED →
 * PROVISIONING → PROVISIONED.
 */
public enum MembershipState {

    /** Membership request received; the registration submission is in flight. */
    SUBMITTED,

    /**
     * The registration was submitted, but no CONFIRMED callback arrived within the submitting
     * call — the registration did not complete synchronously (or the callback never reached this
     * app). EDC resources are not provisioned for such a record.
     */
    REGISTERING,

    /** Registration CONFIRMED by the Onboarding API's status callback; provisioning is next. */
    CONFIRMED,

    /** The participant profile is deployed; EDC resources are coming up. */
    PROVISIONING,

    /** The participant context exists — the member is fully provisioned. Terminal. */
    PROVISIONED,

    /** The registration was rejected by the Onboarding API. Terminal. */
    REJECTED,

    /** A step failed irrecoverably. Terminal. */
    FAILED
}
