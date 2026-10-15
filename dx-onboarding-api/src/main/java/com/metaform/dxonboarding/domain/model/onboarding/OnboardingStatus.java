package com.metaform.dxonboarding.domain.model.onboarding;

/**
 * Where an onboarding request stands. A submission starts at {@code SUBMITTED}; a TSP operator's
 * review moves it on to {@code APPROVED} or {@code REJECTED}, through the intermediate steps of the
 * review and of the provisioning that follows an approval.
 */
public enum OnboardingStatus {
    DRAFT,
    SUBMITTED,
    UNDER_REVIEW,
    ON_HOLD,
    APPROVAL_IN_PROGRESS,
    APPROVAL_FAILED,
    APPROVED,
    REJECTED
}
