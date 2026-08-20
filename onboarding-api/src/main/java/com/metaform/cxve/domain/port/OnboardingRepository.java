package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistration;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import java.util.Optional;

/**
 * Persistence boundary for onboarding state, keeping storage out of the orchestrator's business
 * logic. The default implementation is in-memory; a durable (JPA/JDBC/…) implementation can be
 * swapped in without touching the orchestrator.
 *
 * <p>Besides the process CRUD, this exposes the <em>active-registration</em> queries backing the
 * CX-0006 duplicate checks in {@link RegistrationValidationService}. A registration is active once
 * it {@linkplain OnboardingProcess#isActiveRegistration() passed validation and was not rejected or
 * failed} — i.e. it is in flight or completed. Excluding {@code SUBMITTED} also keeps a
 * registration from matching itself while it is being validated. Matching uses the effective
 * values per {@link PartnerRegistration#of}: a BPN/DID assigned on the process during onboarding
 * takes precedence over the submitted payload.
 */
public interface OnboardingRepository {

    /**
     * Persists a newly submitted onboarding together with the registration payload it was created
     * from.
     */
    void create(OnboardingProcess process, PartnerRegistrationData payload);

    /** Upserts the process after a state transition. */
    void save(OnboardingProcess process);

    Optional<OnboardingProcess> findById(String processId);

    /** The registration payload the onboarding was created from. */
    Optional<PartnerRegistrationData> findPayload(String processId);

    /** The active registration whose effective BPN matches, if any. */
    Optional<PartnerRegistration> findActiveByBpn(String bpn);

    /** The active registration whose effective DID matches, if any. */
    Optional<PartnerRegistration> findActiveByDid(String did);

    /** The active registration holding the given unique id (matched on type and value), if any. */
    Optional<PartnerRegistration> findActiveByUniqueId(CompanyUniqueIdData uniqueId);
}
