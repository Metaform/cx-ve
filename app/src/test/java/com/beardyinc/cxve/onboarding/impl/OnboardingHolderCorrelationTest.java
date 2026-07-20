package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.model.CompanyRoleId;
import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.CredentialIssuanceService;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import com.beardyinc.cxve.onboarding.WalletService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingHolderCorrelationTest {

    // Test doubles for provisioning + issuance; the real services call the tenant manager / IdentityHub.
    private final WalletService wallet = new WalletService() {
        @Override
        public ParticipantProfile provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
            return ParticipantProfile.builder().id("profile-" + process.id()).identifier("did:web:acme").build();
        }

        @Override
        public ParticipantProfile checkProvisionStatus(OnboardingProcess process) {
            return ParticipantProfile.builder()
                    .id("profile-" + process.id())
                    .identifier("did:web:acme")
                    .property("cfm.vpa.state", Map.of("participantContextId", "ctx-1", "holderPid", "holder-pid-1"))
                    .build();
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

    private final InMemoryOnboardingOrchestrator orchestrator = new InMemoryOnboardingOrchestrator(
            new RegistrationValidationServiceStub(),
            new BusinessPartnerNumberServiceStub(),
            new IdentityProofingServiceStub(),
            wallet,
            credentials);

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
