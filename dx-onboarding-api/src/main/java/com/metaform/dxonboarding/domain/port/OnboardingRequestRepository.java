package com.metaform.dxonboarding.domain.port;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.Submission;
import java.util.Optional;

/** Where onboarding requests are kept. */
public interface OnboardingRequestRepository {

    /** Stores a new request, or replaces the one with its id. */
    void save(OnboardingRequest request);

    Optional<OnboardingRequest> findById(String id);

    /** The request this connector already submitted with exactly this content, if any. */
    Optional<OnboardingRequest> findByFingerprint(String connectorId, Submission.Fingerprint fingerprint);

    /** The request holding this DECADE-X-ID, if any (see {@link OnboardingRequest#holdsLegalEntityId()}). */
    Optional<OnboardingRequest> findHolderOfLegalEntityId(String legalEntityId);

    /** How many requests are kept — the sequence business ids are numbered by. */
    long count();
}
