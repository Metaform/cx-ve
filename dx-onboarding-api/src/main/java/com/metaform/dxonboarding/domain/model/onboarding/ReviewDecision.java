package com.metaform.dxonboarding.domain.model.onboarding;

import java.time.Instant;

/**
 * A TSP operator's decision on an onboarding request; the reject fields are null for an approval.
 *
 * @param resubmissionAllowed whether a rejected applicant may submit again
 */
public record ReviewDecision(
        Instant decidedAt,
        RejectReasonCode rejectReasonCode,
        String rejectComment,
        Boolean resubmissionAllowed) {

    public enum RejectReasonCode {
        INCOMPLETE,
        INVALID_LEGAL_ENTITY,
        IDENTITY_VERIFICATION_FAILURE,
        COMPLIANCE_ISSUE,
        OTHER
    }
}
