package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.model.CompanyRoleId;
import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.CredentialIssuanceService;
import com.beardyinc.cxve.onboarding.IdentityProofingService;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import com.beardyinc.cxve.onboarding.OnboardingState;
import com.beardyinc.cxve.onboarding.WalletService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingOrchestratorImplTest {

    private final RegistrationValidationServiceStub validation = new RegistrationValidationServiceStub();
    private final BusinessPartnerNumberServiceStub bpn = new BusinessPartnerNumberServiceStub();

    // Test doubles: the real services call the tenant manager / IdentityHub. Here we return
    // deterministic values so the orchestrator's state machine can be exercised in isolation.
    private final WalletService wallet = new WalletService() {
        @Override
        public ParticipantProfile provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
            return ParticipantProfile.builder().id("wallet-" + process.id()).identifier("did:web:acme").build();
        }

        @Override
        public ParticipantProfile checkProvisionStatus(OnboardingProcess process) {
            // "Ready": context id + holder PID are present, so provisioning polling completes at once.
            return ParticipantProfile.builder()
                    .id("wallet-" + process.id())
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

    private OnboardingOrchestratorImpl orchestratorWith(IdentityProofingService proofing) {
        return new OnboardingOrchestratorImpl(validation, bpn, proofing, wallet, credentials,
                new InMemoryOnboardingRepository());
    }

    private static PartnerRegistrationData registration(String bpn) {
        return new PartnerRegistrationData(
                "Acme Corp", "Berlin", "Musterstrasse", "DE", bpn, "Acme", "BE",
                null, null, null, List.of(), "ext-123", List.of(),
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), null, null, null, null);
    }

    @Test
    void happyPath_runsToCompletion() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start(registration(null));

        // Provisioning is two-phase: the first drive deploys the participant and pauses awaiting the
        // async provisioning result (participant context id + holder PID).
        assertThat(orchestrator.get(id).state()).isEqualTo(OnboardingState.IDENTITY_VERIFIED);
        assertThat(orchestrator.get(id).participantProfileId()).isEqualTo("wallet-" + id);

        // A later drive — as an issuance event would trigger — finds provisioning ready and completes.
        var result = orchestrator.advanceByHolder("did:web:acme");

        assertThat(result).isPresent();
        var process = result.get();
        assertThat(process.state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(process.bpn()).isNotBlank();
        assertThat(process.participantProfileId()).isEqualTo("wallet-" + id);
        assertThat(process.isTerminal()).isTrue();
    }

    @Test
    void reusesSuppliedBpn() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start(registration("BPNL0000000000XY"));

        assertThat(orchestrator.get(id).bpn()).isEqualTo("BPNL0000000000XY");
    }

    @Test
    void invalidRegistration_isRejected() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());
        var missingRoles = new PartnerRegistrationData(
                "Acme Corp", null, null, null, null, null, null,
                null, null, null, null, "ext-1", null, List.of(), null, null, null, null);

        var id = orchestrator.start(missingRoles);

        var process = orchestrator.get(id);
        assertThat(process.state()).isEqualTo(OnboardingState.REJECTED);
        assertThat(process.failureReason()).contains("company role");
    }

    @Test
    void stallsAtIdentityProofingGate_untilVerified() {
        // Proofing not yet complete: the process should stop at BPN_ASSIGNED.
        var pending = new IdentityProofingService() {
            @Override
            public String initiateProofing(OnboardingProcess process) {
                return "proof-pending";
            }

            @Override
            public boolean isVerified(String proofingReference) {
                return false;
            }
        };
        var orchestrator = orchestratorWith(pending);

        var id = orchestrator.start(registration(null));

        assertThat(orchestrator.get(id).state()).isEqualTo(OnboardingState.BPN_ASSIGNED);
        assertThat(orchestrator.get(id).isTerminal()).isFalse();
    }
}
