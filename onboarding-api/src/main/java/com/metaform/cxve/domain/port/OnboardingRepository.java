package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistration;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import java.util.List;
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

    /**
     * Every registration the given OSP client submitted under this externalId — the lookup behind
     * the §2.2.2 conflict check and the read/cancel endpoints, deliberately scoped to the
     * submitting client: another OSP's externalId resolves to empty, never to foreign data. A
     * LIST because (clientId, externalId) is not unique — the legacy flow enforces no uniqueness,
     * and a cancelled tenant registration frees its externalId for resubmission; callers pick
     * deterministically. The conflict check remains racy by design (check-then-create without a
     * unique index) — acceptable for the VE.
     */
    List<OnboardingProcess> findAllByClientIdAndExternalId(String clientId, String externalId);

    /** Every registration the given OSP client has submitted, any state. */
    List<OnboardingProcess> findAllByClientId(String clientId);

    /**
     * Atomically transitions the process to {@link OnboardingState#CANCELLED} — but only while it
     * is still non-terminal: the check and the write are ONE operation, so a cancellation can
     * neither relabel an outcome recorded concurrently nor be based on a stale snapshot.
     *
     * @return true when this call performed the transition; false when the process was already
     *         terminal (or does not exist)
     */
    boolean cancel(String processId, String reason);
}
