package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.HolderRegistrationService;
import com.metaform.cxve.domain.port.IdentityProofingService;
import com.metaform.cxve.adapter.out.stub.BusinessPartnerNumberServiceStub;
import com.metaform.cxve.adapter.out.callback.DefaultRegistrationStatusService;
import com.metaform.cxve.adapter.out.callback.InMemoryCallbackStore;
import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.adapter.out.persistence.InMemoryOnboardingRepository;
import com.metaform.cxve.adapter.out.stub.IdentityProofingServiceStub;
import com.metaform.cxve.adapter.out.validation.RegistrationValidationServiceImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class OnboardingOrchestratorImplTest {

    // Validation checks for duplicates against the same repository the orchestrator persists to.
    private final InMemoryOnboardingRepository repository = new InMemoryOnboardingRepository();
    private final RegistrationValidationServiceImpl validation = new RegistrationValidationServiceImpl(repository);
    private final BusinessPartnerNumberServiceStub bpn = new BusinessPartnerNumberServiceStub();

    // Test double: the real service calls the IssuerService Admin API. Recording the processes it
    // was handed lets tests assert the holder registration happened, and under which identity.
    private static class RecordingHolderRegistration implements HolderRegistrationService {
        final List<OnboardingProcess> registered = new ArrayList<>();

        @Override
        public void registerHolder(OnboardingProcess process, PartnerRegistrationData registrationData) {
            registered.add(process);
        }
    }

    private final RecordingHolderRegistration holderRegistration = new RecordingHolderRegistration();

    private final RecordingOnboardingEventPublisher events = new RecordingOnboardingEventPublisher();

    private OnboardingOrchestratorImpl orchestratorWith(IdentityProofingService proofing) {
        return new OnboardingOrchestratorImpl(validation, bpn, proofing, holderRegistration, repository,
                new DefaultRegistrationStatusService(new InMemoryCallbackStore()), events, RecordingOnboardingEventPublisher.didResolver());
    }

    private static PartnerRegistrationData registration(String bpn) {
        return new PartnerRegistrationData(
                "Acme Corp", "Berlin", "Musterstrasse", "DE", bpn, "Acme", "BE",
                null, null, null, List.of(), "ext-123", List.of(),
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), null, null, null, null);
    }

    /** The DID the resolver derives for {@link #registration}'s short name. */
    private static final String ACME_DID = RecordingOnboardingEventPublisher.DID_TEMPLATE + "Acme";

    @Test
    void happyPath_runsToCompletion() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration(null));

        // With identity proofing satisfied there is no async gate left: the holder is registered
        // and the process completes within the submitting call.
        var process = orchestrator.get(id);
        assertThat(process.state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(process.isTerminal()).isTrue();
        assertThat(process.bpn()).isNotBlank();
        // The submitting client is recorded from the start — it is what status callbacks route by.
        assertThat(process.clientId()).isEqualTo("osp-1");
        // Exactly one holder registration, under the DID seeded at submission.
        assertThat(holderRegistration.registered).hasSize(1);
        assertThat(holderRegistration.registered.get(0).holderId()).isEqualTo(ACME_DID);
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
        // No DID supplied, so it follows the template — the same value the holder registration uses.
        assertThat(event.did()).isEqualTo(ACME_DID);
    }

    @Test
    void start_withoutBpn_announcesTheAssignedBpnOnCompletion() {
        // The BPN is optional on ingress. The started event then honestly carries none — the BPN
        // step has not run yet — and the completed event delivers the one the
        // BusinessPartnerNumberService assigned.
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration(null));

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
    void completion_announcesTheRegisteredIdentities() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));

        assertThat(orchestrator.get(id).state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(events.completed()).hasSize(1);
        var event = events.completed().get(0);
        assertThat(event.processId()).isEqualTo(id);
        assertThat(event.externalId()).isEqualTo("ext-123");
        assertThat(event.bpn()).isEqualTo("BPNL0000000000XY");
        // The DID the holder was registered under.
        assertThat(event.did()).isEqualTo(ACME_DID);
        assertThat(event.state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(event.failureMessage()).isNull();
    }

    @Test
    void rejection_isAnnouncedAsATerminalOutcome() {
        // A rejection ends the onboarding just as definitively as a completion. A subscriber that
        // saw the started event has no other way to learn it is over.
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());
        orchestrator.start("osp-1", registration("BPNL0000000000XY"));

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
                holderRegistration, repository, failingCallback, events,
                RecordingOnboardingEventPublisher.didResolver());

        var thrown = catchThrowable(() -> orchestrator.start("osp-1", registration("BPNL0000000000XY")));

        assertThat(thrown).hasMessage("callback endpoint unreachable");
        var id = events.completed().get(0).processId();
        assertThat(repository.findById(id).orElseThrow().state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(events.completed()).hasSize(1);
        assertThat(events.completed().get(0).state()).isEqualTo(OnboardingState.COMPLETED);
    }

    @Test
    void aThrowingStep_failsTheProcessAndAnnouncesTheOutcome() {
        // start() is the one drive with nothing behind it: only a proofing callback ever re-drives
        // advance(), and only up to its own gate. So an exception must not leave the process
        // suspended between states.
        var unreachableIssuerService = new HolderRegistrationService() {
            @Override
            public void registerHolder(OnboardingProcess process, PartnerRegistrationData registrationData) {
                throw new RuntimeException("IssuerService unreachable");
            }
        };
        var orchestrator = new OnboardingOrchestratorImpl(validation, bpn, new IdentityProofingServiceStub(),
                unreachableIssuerService, repository, new DefaultRegistrationStatusService(new InMemoryCallbackStore()),
                events, RecordingOnboardingEventPublisher.didResolver());

        var thrown = catchThrowable(() -> orchestrator.start("osp-1", registration("BPNL0000000000XY")));

        // The caller still sees the error — it says the synchronous part did not get through.
        assertThat(thrown).hasMessage("IssuerService unreachable");

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
                .contains("IDENTITY_VERIFIED", "IssuerService unreachable");
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
        var orchestrator = new OnboardingOrchestratorImpl(validation, bpn, new IdentityProofingServiceStub(),
                holderRegistration, repository, new DefaultRegistrationStatusService(new InMemoryCallbackStore()),
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
        // advance() is re-driven by callbacks; a terminal process must not re-announce, or a
        // subscriber would see the same onboarding complete repeatedly.
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration("BPNL0000000000XY"));
        orchestrator.advance(id);
        orchestrator.advance(id);

        assertThat(events.completed()).hasSize(1);
        assertThat(holderRegistration.registered).hasSize(1);
    }

    @Test
    void completedOnboarding_isFindableAsActiveRegistration() {
        var orchestrator = orchestratorWith(new IdentityProofingServiceStub());

        var id = orchestrator.start("osp-1", registration(null));

        var process = orchestrator.get(id);
        assertThat(process.state()).isEqualTo(OnboardingState.COMPLETED);
        // The active-registration view carries the BPN and DID assigned during onboarding.
        var persisted = repository.findActiveByDid(ACME_DID);
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
        var failingRegistration = new HolderRegistrationService() {
            @Override
            public void registerHolder(OnboardingProcess process, PartnerRegistrationData registrationData) {
                throw new RuntimeException("holder rejected by the IssuerService");
            }
        };
        var orchestrator = new OnboardingOrchestratorImpl(validation, bpn, new IdentityProofingServiceStub(),
                failingRegistration, repository, new DefaultRegistrationStatusService(new InMemoryCallbackStore()),
                events, RecordingOnboardingEventPublisher.didResolver());

        var thrown = catchThrowable(() -> orchestrator.start("osp-1", registration("BPNL0000000000XY")));
        assertThat(thrown).isNotNull();
        var id = events.started().get(0).processId();

        // Holder registration failed — the process remains stored for audit, but no longer counts
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
        assertThat(process.state()).isEqualTo(OnboardingState.COMPLETED);
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
        assertThat(holderRegistration.registered).isEmpty();
    }
}
