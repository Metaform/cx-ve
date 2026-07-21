package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.PartnerRegistrationData;
import org.springframework.stereotype.Service;

/**
 * Hands a submitted partner registration to the {@link OnboardingOrchestrator}, which drives the
 * CX-0006 onboarding sequence. The endpoint returns as soon as the process is created; progression
 * continues asynchronously.
 */
@Service
public class DefaultNetworkService implements NetworkService {

    private final OnboardingOrchestrator orchestrator;

    public DefaultNetworkService(OnboardingOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void registerPartner(PartnerRegistrationData registrationData) {
        orchestrator.start(registrationData);
    }
}
