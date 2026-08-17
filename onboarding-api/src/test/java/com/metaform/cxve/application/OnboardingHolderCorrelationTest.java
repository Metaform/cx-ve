package com.metaform.cxve.application;

import com.metaform.cxve.adapter.out.callback.InMemoryRegistrationStatusService;
import com.metaform.cxve.adapter.out.persistence.InMemoryOnboardingRepository;
import com.metaform.cxve.adapter.out.stub.BusinessPartnerNumberServiceStub;
import com.metaform.cxve.adapter.out.stub.IdentityProofingServiceStub;
import com.metaform.cxve.adapter.out.validation.RegistrationValidationServiceImpl;
import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.ProvisionedParticipant;
import com.metaform.cxve.domain.port.CredentialIssuanceService;
import com.metaform.cxve.domain.port.WalletService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingHolderCorrelationTest {

    // Test doubles for provisioning + issuance; the real services call the tenant manager / IdentityHub.
    private final WalletService wallet = new WalletService() {
        @Override
        public ProvisionedParticipant provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
            return new ProvisionedParticipant("profile-" + process.id(), "did:web:acme", null, null, null, false);
        }

        @Override
        public ProvisionedParticipant checkProvisionStatus(OnboardingProcess process) {
            return new ProvisionedParticipant("profile-" + process.id(), "did:web:acme", null, "ctx-1", "holder-pid-1", false);
        }
    };

    private final CredentialIssuanceService credentials = new CredentialIssuanceService() {
        @Override
        public boolean issueBpnCredential(OnboardingProcess process) {
            return true;
        }

        @Override
        public boolean issueFrameworkAgreementCredential(OnboardingProcess process) {
            return true;
        }

        @Override
        public boolean issueMembershipCredential(OnboardingProcess process) {
            return true;
        }
    };

    private final InMemoryOnboardingRepository repository = new InMemoryOnboardingRepository();

    private final OnboardingOrchestratorImpl orchestrator = new OnboardingOrchestratorImpl(
            new RegistrationValidationServiceImpl(repository),
            new BusinessPartnerNumberServiceStub(),
            new IdentityProofingServiceStub(),
            wallet,
            credentials,
            repository,
            new InMemoryRegistrationStatusService(),
            new RecordingOnboardingEventPublisher(),
            RecordingOnboardingEventPublisher.didResolver());

    private static PartnerRegistrationData registration() {
        return new PartnerRegistrationData(
                "Acme Corp", "Berlin", "Musterstrasse", "DE", null, "Acme", "BE",
                null, null, null, List.of(), "ext-123", List.of(),
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), null, null, null, null);
    }

    @Test
    void linkedHolder_correlatesEventBackToProcess() {
        // Provisioning links the holder (the participant DID) during the first drive.
        var id = orchestrator.start(registration());

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
