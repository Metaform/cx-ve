package com.metaform.dxonboarding.domain.model.onboarding;

import java.time.Instant;
import java.util.Map;

/**
 * A submitted onboarding request and where it stands.
 *
 * @param id                          the id the applicant reads the request back by
 * @param businessId                  a human-readable reference, e.g. for a TSP operator
 * @param connectorId                 the identity of the connector that submitted it, as the
 *                                    connector's data plane stamped it; only that connector sees it
 * @param fingerprint                 the submitted content, which recognizes a retry
 * @param legalEntityId               the participant's DECADE-X-ID — the TSP's id of the legal
 *                                    entity; null until approval assigns it
 * @param registrationExtractDocument null when none was submitted
 * @param powerOfAttorneyDocument     null when none was submitted
 * @param ucaDocuments                the signed UCA documents, by use case id
 * @param decision                    null until a decision is taken
 */
public record OnboardingRequest(
        String id,
        String businessId,
        String connectorId,
        Instant submittedAt,
        OnboardingStatus status,
        OnboardingRequestData data,
        Submission.Fingerprint fingerprint,
        String legalEntityId,
        DocumentRef gtcDocument,
        DocumentRef registrationExtractDocument,
        DocumentRef powerOfAttorneyDocument,
        Map<String, DocumentRef> ucaDocuments,
        ReviewDecision decision) {

    public OnboardingRequest {
        ucaDocuments = ucaDocuments == null ? Map.of() : Map.copyOf(ucaDocuments);
    }

    /**
     * Approved, and being provisioned under the given DECADE-X-ID: the participant is registered
     * as a credential holder and offered its credentials. Not decided until that completed.
     */
    public OnboardingRequest approvalInProgress(String decadeXId) {
        return with(OnboardingStatus.APPROVAL_IN_PROGRESS, decadeXId, null);
    }

    /** Approved and provisioned: the participant holds its DECADE-X-ID and was offered its credentials. */
    public OnboardingRequest approved(Instant decidedAt) {
        return with(OnboardingStatus.APPROVED, legalEntityId, new ReviewDecision(decidedAt, null, null, null));
    }

    /**
     * Approved, but its provisioning failed. Nothing retries it — there is no TSP operator — so it
     * is final; {@code rejectComment} says what failed, as the decision is the only place the
     * request's view carries an explanation.
     */
    public OnboardingRequest approvalFailed(Instant decidedAt, String reason) {
        return with(OnboardingStatus.APPROVAL_FAILED, legalEntityId, new ReviewDecision(decidedAt, null, reason, null));
    }

    /** Rejected for the given reason. */
    public OnboardingRequest rejected(Instant decidedAt, ReviewDecision.RejectReasonCode code, String comment,
                                      boolean resubmissionAllowed) {
        return with(OnboardingStatus.REJECTED, legalEntityId, new ReviewDecision(decidedAt, code, comment, resubmissionAllowed));
    }

    /** Whether this request holds its DECADE-X-ID: approved, or being provisioned as such. */
    public boolean holdsLegalEntityId() {
        return legalEntityId != null
                && (status == OnboardingStatus.APPROVAL_IN_PROGRESS || status == OnboardingStatus.APPROVED);
    }

    private OnboardingRequest with(OnboardingStatus newStatus, String newLegalEntityId, ReviewDecision newDecision) {
        return new OnboardingRequest(id, businessId, connectorId, submittedAt, newStatus, data, fingerprint,
                newLegalEntityId, gtcDocument, registrationExtractDocument, powerOfAttorneyDocument, ucaDocuments,
                newDecision);
    }
}
