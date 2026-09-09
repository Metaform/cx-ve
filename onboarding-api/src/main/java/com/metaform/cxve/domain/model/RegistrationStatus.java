package com.metaform.cxve.domain.model;

/**
 * The registration status carried on the wire — in status callbacks and the read endpoints'
 * views ({@code applicationStatus}). CX-0009 defines two flow-specific enums — the legacy
 * {@code RegistrationStatus} (with CREATED/INVITE_USER portal states) and
 * {@code OSPTenantRegistrationStatus} — whose intersection is SUBMITTED/CONFIRMED/DECLINED, the
 * only values this implementation ever puts on a callback: no portal invitation phase exists
 * (registrations arrive complete via the API), so one wire enum serves both flows.
 * {@link #CANCELLED} is a cx-ve extension surfaced only by the (beyond-spec) read endpoints —
 * cancellation is OSP-initiated, so no callback ever carries it.
 */
public enum RegistrationStatus {
    SUBMITTED,
    CONFIRMED,
    DECLINED,
    CANCELLED;

    /**
     * The wire status of a process state: every non-terminal state reads as SUBMITTED (in
     * flight — the entry state of the spec's tenant state machine), terminal states map to
     * their outcome.
     */
    public static RegistrationStatus from(OnboardingState state) {
        return switch (state) {
            case SUBMITTED, VALIDATED, BPN_ASSIGNED, IDENTITY_VERIFIED, WALLET_PROVISIONED, CREDENTIALS_ISSUED ->
                    SUBMITTED;
            case COMPLETED -> CONFIRMED;
            case REJECTED, FAILED -> DECLINED;
            case CANCELLED -> CANCELLED;
        };
    }
}
