package com.metaform.dxonboarding.domain.model.onboarding;

/**
 * Where an onboarding request stands. A request starts as a {@code DRAFT}, which the applicant
 * completes and submits ({@code SUBMITTED}); a TSP operator's review moves it on to
 * {@code APPROVED} or {@code REJECTED}, through the intermediate steps of the review and of the
 * provisioning that follows an approval.
 */
public enum OnboardingStatus {
    DRAFT,
    SUBMITTED,
    UNDER_REVIEW,
    ON_HOLD,
    APPROVAL_IN_PROGRESS,
    APPROVAL_FAILED,
    APPROVED,
    REJECTED;

    /** Submitted, and no decision taken yet: what the pending-request quota counts. */
    public boolean awaitsDecision() {
        return this == SUBMITTED || this == UNDER_REVIEW || this == ON_HOLD || this == APPROVAL_IN_PROGRESS;
    }

    /** In progress or approved: a request in this state holds its DID, which no other request may submit. */
    public boolean holdsDid() {
        return awaitsDecision() || this == APPROVED;
    }
}
