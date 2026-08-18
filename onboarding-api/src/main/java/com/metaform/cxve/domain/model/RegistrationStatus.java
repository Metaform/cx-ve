package com.metaform.cxve.domain.model;

import org.springframework.security.core.parameters.P;

public enum RegistrationStatus {
    SUBMITTED,
    CONFIRMED,
    REJECTED;

    public static RegistrationStatus from(OnboardingState state) {
        return switch (state) {
            case SUBMITTED -> SUBMITTED;
            case COMPLETED -> CONFIRMED;
            case REJECTED,FAILED -> REJECTED;
            default -> throw new IllegalArgumentException("Unknown state: " + state);
        };
    }
}
