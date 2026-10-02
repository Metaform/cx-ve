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
 * @param legalEntityId               the TSP's id of the legal entity; null until one is assigned
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
}
