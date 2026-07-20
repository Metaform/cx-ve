package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.model.CompanyRoleId;
import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.IdentityProofingService;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import com.beardyinc.cxve.onboarding.OnboardingState;
import com.beardyinc.cxve.onboarding.WalletService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOnboardingOrchestratorTest {

    private final RegistrationValidationServiceStub validation = new RegistrationValidationServiceStub();
    private final BusinessPartnerNumberServiceStub bpn = new BusinessPartnerNumberServiceStub();
    // Test double: the real ParticipantOnboardingService calls the tenant manager; here we just
    // return a deterministic profile so the orchestrator flow can be exercised in isolation.
    private final WalletService wallet = (process, registrationData) ->
            ParticipantProfile.builder().id("wallet-" + process.id()).identifier("did:web:acme").build();
    private final CredentialIssuanceServiceStub credentials = new CredentialIssuanceServiceStub();

    private InMemoryOnboardingOrchestrator orchestratorWith(IdentityProofingService proofing) {
        return new InMemoryOnboardingOrchestrator(validation, bpn, proofing, wallet, credentials);
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

        var process = orchestrator.get(id);
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
