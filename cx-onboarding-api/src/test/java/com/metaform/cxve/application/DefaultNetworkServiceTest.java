package com.metaform.cxve.application;

import com.metaform.cxve.adapter.out.persistence.InMemoryOnboardingRepository;
import com.metaform.cxve.domain.CancellationNotAllowedException;
import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.ConsentData;
import com.metaform.cxve.domain.model.ConsentKind;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The client-scoped read/cancel operations and the tenant 409 — exercised against the in-memory
 * repository, with a no-op orchestrator (creation-path behavior has its own tests).
 */
class DefaultNetworkServiceTest {

    private final InMemoryOnboardingRepository repository = new InMemoryOnboardingRepository();
    private final RecordingOnboardingEventPublisher events = new RecordingOnboardingEventPublisher();

    private final java.util.concurrent.atomic.AtomicInteger processSequence = new java.util.concurrent.atomic.AtomicInteger();

    private final OnboardingOrchestrator orchestrator = new OnboardingOrchestrator() {
        @Override
        public String start(String clientId, PartnerRegistrationData registrationData) {
            // Unique per attempt, like production's random UUIDs — resubmissions under the same
            // externalId are separate processes, never id collisions.
            var process = OnboardingProcess.submitted("proc-" + processSequence.incrementAndGet(),
                    registrationData.externalId(), registrationData.bpn(), null, clientId);
            repository.create(process, registrationData);
            return process.id();
        }

        @Override
        public OnboardingProcess advance(String processId) {
            return repository.findById(processId).orElseThrow();
        }

        @Override
        public OnboardingProcess get(String processId) {
            return repository.findById(processId).orElseThrow();
        }
    };

    private final DefaultNetworkService service = new DefaultNetworkService(orchestrator, repository, events);

    private static OspTenantRegistrationData tenant(String externalId) {
        return new OspTenantRegistrationData(
                externalId, "Tenant GmbH", "Munich", "Otto-Hahn-Ring", "DE",
                List.of(), List.of(),
                List.of(new ConsentData(ConsentKind.CX_OPERATING_MODEL, null),
                        new ConsentData(ConsentKind.CX_TEN_GOLDEN_RULES, null),
                        new ConsentData(ConsentKind.CX_DATA_EXCHANGE_GOVERNANCE, null)),
                "BY", "Tenant", null, null, null, null);
    }

    @Test
    void registerTenant_rejectsADuplicateExternalIdOfTheSameClient() {
        service.registerTenant("client-1", tenant("ext-1"));

        var thrown = catchThrowable(() -> service.registerTenant("client-1", tenant("ext-1")));

        assertThat(thrown).isInstanceOf(DuplicateRegistrationException.class);
        // Another client may reuse the externalId — uniqueness is per OSP.
        assertThat(catchThrowable(() -> service.registerTenant("client-2", tenant("ext-1")))).isNull();
    }

    @Test
    void registerTenant_aDeclinedAttemptKeepsBlockingItsExternalId() {
        service.registerTenant("client-1", tenant("ext-1"));
        var process = service.getRegistration("client-1", "ext-1");
        repository.save(process.rejected("duplicate BPN"));

        var thrown = catchThrowable(() -> service.registerTenant("client-1", tenant("ext-1")));

        assertThat(thrown).isInstanceOf(DuplicateRegistrationException.class);
    }

    @Test
    void registerTenant_aCancelledAttemptFreesItsExternalId() {
        // The point of cancelling: resubmit corrected data under the same correlation id.
        service.registerTenant("client-1", tenant("ext-1"));
        service.cancelRegistration("client-1", "ext-1");

        assertThat(catchThrowable(() -> service.registerTenant("client-1", tenant("ext-1")))).isNull();

        // Both attempts exist; the read resolves to the live one, not the cancelled husk.
        assertThat(repository.findAllByClientIdAndExternalId("client-1", "ext-1")).hasSize(2);
        assertThat(service.getRegistration("client-1", "ext-1").state()).isNotEqualTo(OnboardingState.CANCELLED);
    }

    @Test
    void getRegistration_picksDeterministicallyAmongDuplicateAttempts() {
        // (clientId, externalId) is not unique (legacy resubmissions, post-cancel re-registration);
        // the selection prefers the attempt still in flight, then a completed one — never an
        // arbitrary row.
        service.registerTenant("client-1", tenant("ext-1"));
        var first = service.getRegistration("client-1", "ext-1");
        service.cancelRegistration("client-1", "ext-1");
        service.registerTenant("client-1", tenant("ext-1"));
        var second = service.getRegistration("client-1", "ext-1");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.state()).isEqualTo(OnboardingState.SUBMITTED);

        // Once the live attempt completes, it also wins over the cancelled one.
        repository.save(second.withState(OnboardingState.COMPLETED));
        assertThat(service.getRegistration("client-1", "ext-1").state()).isEqualTo(OnboardingState.COMPLETED);
    }

    @Test
    void getRegistration_isScopedToTheCallingClient() {
        service.registerTenant("client-1", tenant("ext-1"));

        assertThat(service.getRegistration("client-1", "ext-1").externalId()).isEqualTo("ext-1");
        // A foreign externalId answers exactly like an unknown one — existence must not leak.
        assertThat(catchThrowable(() -> service.getRegistration("client-2", "ext-1")))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(catchThrowable(() -> service.getRegistration("client-1", "unknown")))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listRegistrations_returnsOnlyTheCallersRegistrations() {
        service.registerTenant("client-1", tenant("ext-1"));
        service.registerTenant("client-1", tenant("ext-2"));
        service.registerTenant("client-2", tenant("ext-3"));

        assertThat(service.listRegistrations("client-1"))
                .extracting(OnboardingProcess::externalId)
                .containsExactlyInAnyOrder("ext-1", "ext-2");
        assertThat(service.listRegistrations("client-3")).isEmpty();
    }

    @Test
    void cancelRegistration_terminatesAndAnnounces_butSendsNoCallback() {
        service.registerTenant("client-1", tenant("ext-1"));

        var cancelled = service.cancelRegistration("client-1", "ext-1");

        assertThat(cancelled.state()).isEqualTo(OnboardingState.CANCELLED);
        assertThat(cancelled.isTerminal()).isTrue();
        // Persisted terminally, and inactive: the legacy duplicate checks (BPN/DID/uniqueIds) no
        // longer match it — and per registerTenant_aCancelledAttemptFreesItsExternalId, neither
        // does the tenant flow's externalId conflict check.
        assertThat(repository.findById(cancelled.id()).orElseThrow().state()).isEqualTo(OnboardingState.CANCELLED);
        assertThat(repository.findById(cancelled.id()).orElseThrow().isActiveRegistration()).isFalse();
        // Announced as a terminal outcome for subscribers...
        assertThat(events.completed()).hasSize(1);
        assertThat(events.completed().get(0).state()).isEqualTo(OnboardingState.CANCELLED);
        assertThat(events.completed().get(0).externalId()).isEqualTo("ext-1");
    }

    @Test
    void cancelRegistration_isScopedToTheCallingClient() {
        service.registerTenant("client-1", tenant("ext-1"));

        assertThat(catchThrowable(() -> service.cancelRegistration("client-2", "ext-1")))
                .isInstanceOf(NoSuchElementException.class);
        // Untouched: still cancellable by its owner.
        assertThat(service.cancelRegistration("client-1", "ext-1").state()).isEqualTo(OnboardingState.CANCELLED);
    }

    @Test
    void cancelRegistration_ofATerminalRegistration_isRefused() {
        service.registerTenant("client-1", tenant("ext-1"));
        var process = service.getRegistration("client-1", "ext-1");
        repository.save(process.withState(OnboardingState.COMPLETED));

        var thrown = catchThrowable(() -> service.cancelRegistration("client-1", "ext-1"));

        assertThat(thrown).isInstanceOf(CancellationNotAllowedException.class)
                .hasMessageContaining("COMPLETED");
        // The recorded outcome stays; nothing was announced.
        assertThat(service.getRegistration("client-1", "ext-1").state()).isEqualTo(OnboardingState.COMPLETED);
        assertThat(events.completed()).isEmpty();
    }
}
