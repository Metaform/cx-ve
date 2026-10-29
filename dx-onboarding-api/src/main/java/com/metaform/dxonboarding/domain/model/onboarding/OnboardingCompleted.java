package com.metaform.dxonboarding.domain.model.onboarding;

/**
 * Announced when an onboarding request reached its outcome — any of them, so {@code state} says
 * how it ended.
 *
 * @param processId      the request's id, matching the {@link OnboardingStarted} it closes
 * @param externalId     the applicant's reference (the Membership Hub's external id)
 * @param did            the participant's DID
 * @param decadeXId      the participant's DECADE-X-ID; null unless it was approved
 * @param state          how it ended
 * @param failureMessage why it was rejected or failed; null when it completed
 */
public record OnboardingCompleted(String processId, String externalId, String did, String decadeXId, State state,
                                  String failureMessage) {

    /** Named as the onboarding lifecycle events of the platform's observers name them. */
    public enum State {
        /** Approved: the participant holds its DECADE-X-ID and was offered its credentials. */
        COMPLETED,
        REJECTED,
        /** Approved, but the provisioning that follows failed. */
        FAILED
    }

    public static OnboardingCompleted of(OnboardingRequest request) {
        var state = switch (request.status()) {
            case APPROVED -> State.COMPLETED;
            case REJECTED -> State.REJECTED;
            case APPROVAL_FAILED -> State.FAILED;
            default -> throw new IllegalArgumentException("Onboarding request %s has not ended (%s)"
                    .formatted(request.id(), request.status()));
        };
        var reason = request.decision() == null ? null : request.decision().rejectComment();
        return new OnboardingCompleted(request.id(), request.data().applicantReference(), request.connectorId(),
                state == State.COMPLETED ? request.legalEntityId() : null, state,
                state == State.COMPLETED ? null : reason);
    }
}
