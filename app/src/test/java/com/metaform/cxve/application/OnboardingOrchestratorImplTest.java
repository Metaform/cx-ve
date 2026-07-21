package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.ProvisionedParticipant;
import com.metaform.cxve.domain.port.CredentialIssuanceService;
import com.metaform.cxve.domain.port.IdentityProofingService;
import com.metaform.cxve.domain.port.WalletService;
import com.metaform.cxve.adapter.out.stub.BusinessPartnerNumberServiceStub;
import com.metaform.cxve.adapter.out.persistence.InMemoryOnboardingRepository;
import com.metaform.cxve.adapter.out.stub.IdentityProofingServiceStub;
import com.metaform.cxve.adapter.out.validation.RegistrationValidationServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OnboardingOrchestratorImplTest {

    // Validation checks for duplicates against the same repository the orchestrator persists to.
    private final InMemoryOnboardingRepository repository = new InMemoryOnboardingRepository();
    private final RegistrationValidationServiceImpl validation = new RegistrationValidationServiceImpl(repository);
    private final BusinessPartnerNumberServiceStub bpn = new BusinessPartnerNumberServiceStub();

    // Test doubles: the real services call the tenant manager / IdentityHub. Here we return
    // deterministic values so the orchestrator's state machine can be exercised in isolation.
    private final WalletService wallet = new WalletService() {
        @Override
        public ProvisionedParticipant provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
            return new ProvisionedParticipant("wallet-" + process.id(), "did:web:acme", null, null, null, false);
        }

        @Override
        public ProvisionedParticipant checkProvisionStatus(OnboardingProcess process) {
            // "Ready": context id + holder PID are present, so provisioning polling completes at once.
            return new ProvisionedParticipant("wallet-" + process.id(), "did:web:acme", null, "ctx-1", "holder-pid-1", false);
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
        return new OnboardingOrchestratorImpl(validation, bpn, proofing, wallet, credentials, repository);
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
    void completedOnboarding_isFindableAsActiveRegistration() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start(registration(null));
        var process = orchestrator.advanceByHolder("did:web:acme").orElseThrow();

        assertThat(process.state()).isEqualTo(OnboardingState.COMPLETED);
        // The active-registration view carries the BPN and DID assigned during onboarding.
        var persisted = repository.findActiveByDid("did:web:acme");
        assertThat(persisted).isPresent();
        assertThat(persisted.get().processId()).isEqualTo(id);
        assertThat(persisted.get().state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(persisted.get().data().bpn()).isEqualTo(process.bpn());
        assertThat(repository.findActiveByBpn(process.bpn())).isPresent();
    }

    @Test
    void duplicateRegistration_isRejected() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        orchestrator.start(registration("BPNL0000000000XY"));
        orchestrator.advanceByHolder("did:web:acme");

        var secondId = orchestrator.start(registration("BPNL0000000000XY"));

        var second = orchestrator.get(secondId);
        assertThat(second.state()).isEqualTo(OnboardingState.REJECTED);
        assertThat(second.failureReason()).contains("already registered");
    }

    @Test
    void inFlightRegistration_isRecordedAtValidation_andBlocksDuplicates() {
        // Proofing never completes, so the first registration stalls mid-flight at BPN_ASSIGNED.
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

        orchestrator.start(registration("BPNL0000000000XY"));

        var entry = repository.findActiveByBpn("BPNL0000000000XY");
        assertThat(entry).isPresent();
        assertThat(entry.get().state()).isEqualTo(OnboardingState.BPN_ASSIGNED);
        assertThat(entry.get().inFlight()).isTrue();

        var secondId = orchestrator.start(registration("BPNL0000000000XY"));

        var second = orchestrator.get(secondId);
        assertThat(second.state()).isEqualTo(OnboardingState.REJECTED);
        assertThat(second.failureReason()).contains("already in flight");
    }

    @Test
    void failedOnboarding_doesNotBlockReRegistration() {
        var failingCredentials = new CredentialIssuanceService() {
            @Override
            public boolean issueBpnCredential(OnboardingProcess process) {
                return false;
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
        var orchestrator = new OnboardingOrchestratorImpl(validation, bpn, new IdentityProofingServiceStub(),
                wallet, failingCredentials, repository);

        var id = orchestrator.start(registration("BPNL0000000000XY"));
        orchestrator.advanceByHolder("did:web:acme");

        // Credential issuance failed — the process remains stored for audit, but no longer counts
        // as an active registration, so the partner can re-register.
        assertThat(orchestrator.get(id).state()).isEqualTo(OnboardingState.FAILED);
        assertThat(repository.findById(id)).isPresent();
        assertThat(repository.findActiveByBpn("BPNL0000000000XY")).isEmpty();
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
