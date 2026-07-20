package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.infrastructure.cfm.TenantManagerClient;
import com.beardyinc.cxve.model.CompanyRoleId;
import com.beardyinc.cxve.model.PartnerRegistrationData;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingHolderCorrelationTest {

    @Mock
    private TenantManagerClient tenantManagerClient;
    private final InMemoryOnboardingOrchestrator orchestrator = new InMemoryOnboardingOrchestrator(
            new RegistrationValidationServiceStub(),
            new BusinessPartnerNumberServiceStub(),
            new IdentityProofingServiceStub(),
            new ParticipantOnboardingService(tenantManagerClient),
            new CredentialIssuanceServiceStub());

    private static PartnerRegistrationData registration() {
        return new PartnerRegistrationData(
                "Acme Corp", "Berlin", "Musterstrasse", "DE", null, "Acme", "BE",
                null, null, null, List.of(), "ext-123", List.of(),
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), null, null, null, null);
    }

    @Test
    void linkedHolder_correlatesEventBackToProcess() {
        var id = orchestrator.start(registration());
        orchestrator.linkHolder(id, "did:web:acme");

        var result = orchestrator.advanceByHolder("did:web:acme");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
        assertThat(result.get().holderId()).isEqualTo("did:web:acme");
    }

    @Test
    void unknownHolder_yieldsEmpty() {
        assertThat(orchestrator.advanceByHolder("did:web:nobody")).isEmpty();
    }
}
