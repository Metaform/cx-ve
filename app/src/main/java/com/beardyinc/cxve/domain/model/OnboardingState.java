package com.beardyinc.cxve.domain.model;

/**
 * Lifecycle of a partner onboarding, following the CX-0006 post-submission process.
 * The happy path runs top to bottom; {@link #REJECTED} and {@link #FAILED} are terminal off-ramps.
 */
public enum OnboardingState {

    /** Registration data received from the OSP, not yet triaged. */
    SUBMITTED,

    /** Operator (CSP-B) declined the request during initial triage. Terminal. */
    REJECTED,

    /** Duplicate / in-flight / certificate checks passed (CX-0006 §validation). */
    VALIDATED,

    /** A BPNL has been resolved or created for the applicant (per CX-0010). */
    BPN_ASSIGNED,

    /** Identity of the prospective participant has been verified (identity proofing). */
    IDENTITY_VERIFIED,

    /** A CX-0149-compliant wallet is available to receive credentials. */
    WALLET_PROVISIONED,

    /** BPN, Framework Agreement and Membership credentials have been issued. */
    CREDENTIALS_ISSUED,

    /** Membership credential issued — onboarding complete. Terminal. */
    COMPLETED,

    /** A step failed irrecoverably. Terminal. */
    FAILED
}
