package com.metaform.dxonboarding.domain.model;

/**
 * The outcome of an application, as the decision webhook reports it.
 *
 * @param applicationRef the submitter's reference of the application
 * @param applicationId  the id this API assigned on receipt
 * @param decision       APPROVED or REJECTED
 * @param reason         why it was rejected; null for an approval
 */
public record Decision(String applicationRef, String applicationId, Outcome decision, String reason) {

    public enum Outcome {
        APPROVED,
        REJECTED
    }

    public static Decision approved(String applicationRef, String applicationId) {
        return new Decision(applicationRef, applicationId, Outcome.APPROVED, null);
    }

    public static Decision rejected(String applicationRef, String applicationId, String reason) {
        return new Decision(applicationRef, applicationId, Outcome.REJECTED, reason);
    }
}
