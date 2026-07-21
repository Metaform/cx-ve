package com.beardyinc.cxve.onboarding;

import com.beardyinc.cxve.model.PartnerRegistrationData;

import java.util.Optional;

/**
 * Persistence boundary for onboarding state, keeping storage out of the orchestrator's business
 * logic. The default implementation is in-memory; a durable (JPA/JDBC/…) implementation can be
 * swapped in without touching the orchestrator.
 */
public interface OnboardingRepository {

    /**
     * Persists a newly submitted onboarding together with the registration payload it was created
     * from.
     */
    void create(OnboardingProcess process, PartnerRegistrationData payload);

    /**
     * Upserts the process after a state transition. Implementations must keep any holder-id lookup
     * consistent with {@link OnboardingProcess#holderId()}.
     */
    void save(OnboardingProcess process);

    Optional<OnboardingProcess> findById(String processId);

    /** The process linked to the given holder id (the DID issuance events correlate on), if any. */
    Optional<OnboardingProcess> findByHolderId(String holderId);

    /** The registration payload the onboarding was created from. */
    Optional<PartnerRegistrationData> findPayload(String processId);
}
