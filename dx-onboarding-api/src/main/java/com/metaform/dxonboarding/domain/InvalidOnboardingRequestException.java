package com.metaform.dxonboarding.domain;

import java.util.List;

/** A submission that is incomplete or malformed; nothing of it was stored. */
public class InvalidOnboardingRequestException extends RuntimeException {

    private final List<String> violations;

    public InvalidOnboardingRequestException(List<String> violations) {
        super("Invalid onboarding request: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
