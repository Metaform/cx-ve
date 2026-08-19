package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.ProvisionedParticipant;
import com.metaform.cxve.domain.port.CredentialIssuanceService;
import com.metaform.cxve.domain.port.IdentityProofingService;
import com.metaform.cxve.domain.port.WalletService;
import com.metaform.cxve.adapter.out.stub.BusinessPartnerNumberServiceStub;
import com.metaform.cxve.adapter.out.callback.DefaultRegistrationStatusService;
import com.metaform.cxve.adapter.out.callback.InMemoryCallbackStore;
import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.adapter.out.persistence.InMemoryOnboardingRepository;
import com.metaform.cxve.adapter.out.stub.IdentityProofingServiceStub;
import com.metaform.cxve.adapter.out.validation.RegistrationValidationServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    private final RecordingOnboardingEventPublisher events = new RecordingOnboardingEventPublisher();

    private OnboardingOrchestratorImpl orchestratorWith(IdentityProofingService proofing) {
        return new OnboardingOrchestratorImpl(validation, bpn, proofing, wallet, credentials, repository,
                new DefaultRegistrationStatusService(new InMemoryCallbackStore()), events, RecordingOnboardingEventPublisher.didResolver());
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

        var id = orchestrator.start("osp-1", registration(null));

        // Provisioning is two-phase: the first drive deploys the participant and pauses awaiting the
        // async provisioning result (participant context id + holder PID).
        assertThat(orchestrator.get(id).state()).isEqualTo(OnboardingState.IDENTITY_VERIFIED);
        assertThat(orchestrator.get(id).participantProfileId()).isEqualTo("wallet-" + id);
        // The submitting client is recorded from the start — it is what status callbacks route by.
        assertThat(orchestrator.get(id).clientId()).isEqualTo("osp-1");

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
    void start_announcesTheOnboardingWithBothIdentities() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));

        assertThat(events.started()).hasSize(1);
        var event = events.started().get(0);
        assertThat(event.processId()).isEqualTo(id);
        assertThat(event.externalId()).isEqualTo("ext-123");
        assertThat(event.bpn()).isEqualTo("BPNL0000000000XY");
        // No DID supplied, so it follows the template — the same value provisioning will use.
        assertThat(event.did()).isEqualTo(RecordingOnboardingEventPublisher.DID_TEMPLATE + "Acme");
    }

    @Test
    void start_withoutBpn_announcesTheAssignedBpnOnCompletion() {
        // The BPN is optional on ingress. The started event then honestly carries none — the BPN
        // step has not run yet — and the completed event delivers the one the
        // BusinessPartnerNumberService assigned.
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration(null));
        orchestrator.advanceByHolder("did:web:acme");

        assertThat(events.started().get(0).bpn()).isNull();
        var assigned = orchestrator.get(id).bpn();
        assertThat(assigned).isNotBlank();
        assertThat(events.completed().get(0).bpn()).isEqualTo(assigned);
    }

    @Test
    void start_announcesTheCallerSuppliedDidWhenGiven() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());
        var supplied = registration("BPNL0000000000XY").withDid("did:web:acme.example.com");

        orchestrator.start("osp-1", supplied);

        assertThat(events.started().get(0).did()).isEqualTo("did:web:acme.example.com");
    }

    @Test
    void completion_announcesTheProvisionedIdentities() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        var process = orchestrator.advanceByHolder("did:web:acme").orElseThrow();

        assertThat(process.state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(events.completed()).hasSize(1);
        var event = events.completed().get(0);
        assertThat(event.processId()).isEqualTo(id);
        assertThat(event.externalId()).isEqualTo("ext-123");
        assertThat(event.bpn()).isEqualTo("BPNL0000000000XY");
        // The DID actually provisioned (the linked holder), not the one predicted at start.
        assertThat(event.did()).isEqualTo("did:web:acme");
        assertThat(event.participantContextId()).isEqualTo(process.participantContextId());
        assertThat(event.state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(event.failureMessage()).isNull();
    }

    @Test
    void aRejectedDuplicate_doesNotStealTheHolderCorrelation() {
        // The holder DID is seeded at submission, so a rejected duplicate carries the same one as
        // the onboarding it duplicated. An issuance event for that DID must still reach the
        // onboarding that is running, not the rejected attempt.
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
        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        var duplicateId = orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        assertThat(orchestrator.get(duplicateId).state()).isEqualTo(OnboardingState.REJECTED);

        var advanced = orchestrator.advanceByHolder(RecordingOnboardingEventPublisher.DID_TEMPLATE + "Acme");

        assertThat(advanced).isPresent();
        assertThat(advanced.get().id()).isEqualTo(id);
    }

    @Test
    void rejection_isAnnouncedAsATerminalOutcome() {
        // A rejection ends the onboarding just as definitively as a completion. A subscriber that
        // saw the started event has no other way to learn it is over.
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());
        orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        orchestrator.advanceByHolder("did:web:acme");

        var rejectedId = orchestrator.start("osp-1", registration("BPNL0000000000XY"));

        assertThat(orchestrator.get(rejectedId).state()).isEqualTo(OnboardingState.REJECTED);
        var event = events.completed().stream()
                .filter(e -> e.processId().equals(rejectedId))
                .findFirst()
                .orElseThrow();
        assertThat(event.state()).isEqualTo(OnboardingState.REJECTED);
        assertThat(event.failureMessage()).contains("already registered");
        // Two registrations, two started events, two terminal announcements: every onboarding that
        // is announced as started is also announced as finished.
        assertThat(events.started()).hasSize(2);
        assertThat(events.completed()).hasSize(2);
    }

    @Test
    void completion_isAnnouncedEvenWhenTheStatusCallbackFails() {
        // The status callback is an outbound call to the onboarding service provider. It must not be
        // able to suppress the event — otherwise an unreachable third party silently costs every
        // subscriber the completion of a successful onboarding.
        var failingCallback = new RegistrationStatusService() {
            @Override
            public CallbackRequestData getCallbackAddress(String clientId) {
                return null;
            }

            @Override
            public void setCallbackAddress(String clientId, CallbackRequestData callbackData) {
            }

            @Override
            public void invokeCallback(OnboardingProcess after) {
                throw new IllegalStateException("callback endpoint unreachable");
            }
        };
        var orchestrator = new OnboardingOrchestratorImpl(validation, bpn, new IdentityProofingServiceStub(),
                wallet, credentials, repository, failingCallback, events,
                RecordingOnboardingEventPublisher.didResolver());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        catchThrowable(() -> orchestrator.advanceByHolder("did:web:acme"));

        assertThat(repository.findById(id).orElseThrow().state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(events.completed()).hasSize(1);
        assertThat(events.completed().get(0).state()).isEqualTo(OnboardingState.COMPLETED);
    }

    @Test
    void aThrowingStep_failsTheProcessAndAnnouncesTheOutcome() {
        // start() is the one drive with nothing behind it: the issuance path gets its message nak'd
        // and redelivered, but here nothing re-drives advance(), and the holder link advanceByHolder
        // would need is only established once provisioning returns. So an exception must not leave
        // the process suspended between states.
        var unreachableTenantManager = new WalletService() {
            @Override
            public ProvisionedParticipant provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
                throw new RuntimeException("No cell found in CFM Tenant Manager");
            }

            @Override
            public ProvisionedParticipant checkProvisionStatus(OnboardingProcess process) {
                throw new UnsupportedOperationException("not reached");
            }
        };
        var orchestrator = new OnboardingOrchestratorImpl(validation, bpn, new IdentityProofingServiceStub(),
                unreachableTenantManager, credentials, repository, new DefaultRegistrationStatusService(new InMemoryCallbackStore()),
                events, RecordingOnboardingEventPublisher.didResolver());

        var thrown = catchThrowable(() -> orchestrator.start("osp-1", registration("BPNL0000000000XY")));

        // The caller still sees the error — it says the synchronous part did not get through.
        assertThat(thrown).hasMessage("No cell found in CFM Tenant Manager");

        assertThat(events.started()).hasSize(1);
        var processId = events.started().get(0).processId();
        // Terminal rather than wedged at IDENTITY_VERIFIED, so it no longer counts as in flight and
        // the partner can retry the registration.
        assertThat(repository.findById(processId).orElseThrow().state()).isEqualTo(OnboardingState.FAILED);
        assertThat(repository.findActiveByBpn("BPNL0000000000XY")).isEmpty();

        assertThat(events.completed()).hasSize(1);
        var event = events.completed().get(0);
        assertThat(event.processId()).isEqualTo(processId);
        assertThat(event.state()).isEqualTo(OnboardingState.FAILED);
        // The step it died at, and why: without both, a subscriber sees only that it ended.
        assertThat(event.failureMessage())
                .contains("IDENTITY_VERIFIED", "No cell found in CFM Tenant Manager");
    }

    @Test
    void anErrorAfterATerminalOutcome_doesNotRelabelIt() {
        // The failure handler runs on the way out of start(), by which point the outcome may already
        // be recorded and announced. Reporting that rejection as a failure, and announcing it a
        // second time, would be worse than the error being reacted to.
        var throwsAfterRecording = new RecordingOnboardingEventPublisher() {
            @Override
            public void onboardingCompleted(OnboardingCompleted event) {
                super.onboardingCompleted(event);
                throw new IllegalStateException("publisher blew up");
            }
        };
        var completedFirst = orchestratorWith(new IdentityProofingServiceStub());
        completedFirst.start("osp-1", registration("BPNL0000000000XY"));
        completedFirst.advanceByHolder("did:web:acme");
        var orchestrator = new OnboardingOrchestratorImpl(validation, bpn, new IdentityProofingServiceStub(),
                wallet, credentials, repository, new DefaultRegistrationStatusService(new InMemoryCallbackStore()),
                throwsAfterRecording, RecordingOnboardingEventPublisher.didResolver());

        // Duplicate of the completed registration, so it is rejected inside start() itself.
        var thrown = catchThrowable(() -> orchestrator.start("osp-1", registration("BPNL0000000000XY")));

        assertThat(thrown).hasMessage("publisher blew up");
        assertThat(throwsAfterRecording.completed()).hasSize(1);
        var event = throwsAfterRecording.completed().get(0);
        assertThat(event.state()).isEqualTo(OnboardingState.REJECTED);
        assertThat(repository.findById(event.processId()).orElseThrow().state())
                .isEqualTo(OnboardingState.REJECTED);
    }

    @Test
    void completion_isAnnouncedOnlyOnceEvenWhenReDriven() {
        // advance() is re-driven by callbacks and issuance events; a terminal process must not
        // re-announce, or a subscriber would see the same onboarding complete repeatedly.
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        orchestrator.advanceByHolder("did:web:acme");
        orchestrator.advance(id);
        orchestrator.advance(id);

        assertThat(events.completed()).hasSize(1);
    }

    @Test
    void completedOnboarding_isFindableAsActiveRegistration() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration(null));
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

        orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        orchestrator.advanceByHolder("did:web:acme");

        var secondId = orchestrator.start("osp-1", registration("BPNL0000000000XY"));

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

        orchestrator.start("osp-1", registration("BPNL0000000000XY"));

        var entry = repository.findActiveByBpn("BPNL0000000000XY");
        assertThat(entry).isPresent();
        assertThat(entry.get().state()).isEqualTo(OnboardingState.BPN_ASSIGNED);
        assertThat(entry.get().inFlight()).isTrue();

        var secondId = orchestrator.start("osp-1", registration("BPNL0000000000XY"));

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
                wallet, failingCredentials, repository, new DefaultRegistrationStatusService(new InMemoryCallbackStore()),
                events, RecordingOnboardingEventPublisher.didResolver());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        orchestrator.advanceByHolder("did:web:acme");

        // Credential issuance failed — the process remains stored for audit, but no longer counts
        // as an active registration, so the partner can re-register.
        assertThat(orchestrator.get(id).state()).isEqualTo(OnboardingState.FAILED);
        assertThat(repository.findById(id)).isPresent();
        assertThat(repository.findActiveByBpn("BPNL0000000000XY")).isEmpty();

        // A terminal outcome is announced whichever way it ends: a subscriber waiting on this
        // onboarding must not be left hanging just because it failed.
        assertThat(events.completed()).hasSize(1);
        var event = events.completed().get(0);
        assertThat(event.state()).isEqualTo(OnboardingState.FAILED);
        assertThat(event.failureMessage()).isEqualTo(orchestrator.get(id).failureReason());
    }

    @Test
    void reusesSuppliedBpn() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));

        assertThat(orchestrator.get(id).bpn()).isEqualTo("BPNL0000000000XY");
    }

    @Test
    void shapeViolation_isNotTheOrchestratorsConcern() {
        // Shape validation (required fields such as companyRoles) happens at the web boundary —
        // via the annotations on PartnerRegistrationData and InvalidRequestShapeHandler — so the
        // orchestrator trusts the shape and only performs logical validation (duplicates/in-flight).
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());
        var missingRoles = new PartnerRegistrationData(
                "Acme Corp", null, null, null, null, null, null,
                null, null, null, null, "ext-1", null, List.of(), null, null, null, null);

        var id = orchestrator.start("osp-1", missingRoles);

        var process = orchestrator.get(id);
        assertThat(process.state()).isEqualTo(OnboardingState.IDENTITY_VERIFIED);
        assertThat(process.failureReason()).isNull();
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

        var id = orchestrator.start("osp-1", registration(null));

        assertThat(orchestrator.get(id).state()).isEqualTo(OnboardingState.BPN_ASSIGNED);
        assertThat(orchestrator.get(id).isTerminal()).isFalse();
    }
}
