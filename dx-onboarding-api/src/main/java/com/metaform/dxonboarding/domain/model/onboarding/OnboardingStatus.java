package com.metaform.dxonboarding.domain.model.onboarding;

/**
 * Where an onboarding request stands. A request starts as a {@code DRAFT}, which the applicant
 * completes and submits ({@code SUBMITTED}); the review takes it on ({@code UNDER_REVIEW}) and
 * moves it on to {@code APPROVED} or {@code REJECTED}, through the provisioning that follows an
 * approval — or hands it back to the applicant ({@code CHANGES_REQUESTED}), who corrects it and
 * submits it again ({@code UNDER_REVIEW}).
 */
public enum OnboardingStatus {
    DRAFT,
    SUBMITTED,
    UNDER_REVIEW,
    CHANGES_REQUESTED,
    APPROVAL_IN_PROGRESS,
    APPROVAL_FAILED,
    APPROVED,
    REJECTED;

    /** The applicant may change the request: a draft, or a request the review asked changes of. */
    public boolean editable() {
        return this == DRAFT || this == CHANGES_REQUESTED;
    }

    /** Submitted, and no decision taken yet: what the pending-request quota counts. */
    public boolean awaitsDecision() {
        return this == SUBMITTED || this == UNDER_REVIEW || this == CHANGES_REQUESTED || this == APPROVAL_IN_PROGRESS;
    }

    /** In progress or approved: a request in this state holds its DID, which no other request may submit. */
    public boolean holdsDid() {
        return awaitsDecision() || this == APPROVED;
    }
}
