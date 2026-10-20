package com.metaform.dxonboarding.domain.port;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Where onboarding requests are kept. */
public interface OnboardingRequestRepository {

    /** Stores a new request, or replaces the one with its id. */
    void save(OnboardingRequest request);

    void delete(String id);

    Optional<OnboardingRequest> findById(String id);

    /** Every request the connector created, whatever its status. */
    List<OnboardingRequest> findByConnector(String connectorId);

    /** Every request whose {@code legalEntity.preferredDid} is this DID (as normalized), whatever its status. */
    List<OnboardingRequest> findByPreferredDid(String did);

    /** The request holding this DECADE-X-ID, if any (see {@link OnboardingRequest#holdsLegalEntityId()}). */
    Optional<OnboardingRequest> findHolderOfLegalEntityId(String legalEntityId);

    /** The drafts last changed before the given instant. */
    List<OnboardingRequest> findDraftsUpdatedBefore(Instant instant);

    /** The next number of the sequence business ids are numbered by: never handed out before, even after a delete. */
    long nextBusinessNumber();
}
