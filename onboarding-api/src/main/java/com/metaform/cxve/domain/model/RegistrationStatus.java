package com.metaform.cxve.domain.model;

/**
 * The registration status carried on the wire in status callbacks ({@code applicationStatus}).
 * CX-0009 defines two flow-specific enums — the legacy {@code RegistrationStatus} (with
 * CREATED/INVITE_USER portal states) and {@code OSPTenantRegistrationStatus} — but the three
 * values here are their intersection, and the only ones this implementation ever emits: no
 * portal invitation phase exists (registrations arrive complete via the API), so one wire enum
 * serves both flows.
 */
public enum RegistrationStatus {
    SUBMITTED,
    CONFIRMED,
    DECLINED;

    public static RegistrationStatus from(OnboardingState state) {
        return switch (state) {
            case SUBMITTED -> SUBMITTED;
            case COMPLETED -> CONFIRMED;
            case REJECTED, FAILED -> DECLINED;
            default -> throw new IllegalArgumentException("Unknown state: " + state);
        };
    }
}
