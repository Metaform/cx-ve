package com.metaform.cxve.application;

import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.OnboardingRepository;
import org.springframework.stereotype.Service;

/**
 * Hands a submitted registration to the {@link OnboardingOrchestrator}, which drives the CX-0006
 * onboarding sequence. The endpoints return as soon as the process is created; progression
 * continues asynchronously. The tenant flow (§2.2.2) additionally enforces the spec's per-OSP
 * externalId uniqueness before anything is created — the legacy flow deliberately does not (the
 * spec declares no 409 there; its duplicate checks reject via the DECLINED callback instead).
 */
@Service
public class DefaultNetworkService implements NetworkService {

    private final OnboardingOrchestrator orchestrator;
    private final OnboardingRepository repository;

    public DefaultNetworkService(OnboardingOrchestrator orchestrator, OnboardingRepository repository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
    }

    @Override
    public String registerPartner(String clientId, PartnerRegistrationData registrationData) {
        return orchestrator.start(clientId, registrationData);
    }

    @Override
    public String registerTenant(String clientId, OspTenantRegistrationData tenantData) {
        if (repository.existsByClientIdAndExternalId(clientId, tenantData.externalId())) {
            throw new DuplicateRegistrationException(tenantData.externalId());
        }
        return orchestrator.start(clientId, tenantData.toRegistrationData());
    }
}
