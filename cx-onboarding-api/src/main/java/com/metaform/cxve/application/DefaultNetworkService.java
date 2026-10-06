package com.metaform.cxve.application;

import com.metaform.cxve.domain.CancellationNotAllowedException;
import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.OnboardingEventPublisher;
import com.metaform.cxve.domain.port.OnboardingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hands a submitted registration to the {@link OnboardingOrchestrator}, which drives the CX-0006
 * onboarding sequence, and serves the client-scoped read/cancel operations on top (BEYOND-SPEC
 * extensions — the spec declares neither, leaving a lost callback unrecoverable). The endpoints
 * return as soon as the process is created; progression continues asynchronously. The tenant flow
 * (§2.2.2) additionally enforces the spec's per-OSP externalId uniqueness before anything is
 * created — the legacy flow deliberately does not (the spec declares no 409 there; its duplicate
 * checks reject via the DECLINED callback instead).
 */
@Service
public class DefaultNetworkService implements NetworkService {

    private static final Logger log = LoggerFactory.getLogger(DefaultNetworkService.class);

    private final OnboardingOrchestrator orchestrator;
    private final OnboardingRepository repository;
    private final OnboardingEventPublisher eventPublisher;

    public DefaultNetworkService(OnboardingOrchestrator orchestrator,
                                 OnboardingRepository repository,
                                 OnboardingEventPublisher eventPublisher) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String registerPartner(String clientId, PartnerRegistrationData registrationData) {
        return orchestrator.start(clientId, registrationData);
    }

    @Override
    public String registerTenant(String clientId, OspTenantRegistrationData tenantData) {
        // The §2.2.2 conflict check counts every earlier attempt EXCEPT cancelled ones: a
        // cancellation exists precisely so the OSP can resubmit corrected data under the same
        // correlation id — declined/failed/completed/in-flight attempts keep blocking it.
        var blocking = repository.findAllByClientIdAndExternalId(clientId, tenantData.externalId()).stream()
                .anyMatch(p -> p.state() != OnboardingState.CANCELLED);
        if (blocking) {
            throw new DuplicateRegistrationException(tenantData.externalId());
        }
        return orchestrator.start(clientId, tenantData.toRegistrationData());
    }

    @Override
    public OnboardingProcess getRegistration(String clientId, String externalId) {
        return select(repository.findAllByClientIdAndExternalId(clientId, externalId))
                .orElseThrow(() -> notFound(externalId));
    }

    @Override
    public List<OnboardingProcess> listRegistrations(String clientId) {
        return repository.findAllByClientId(clientId);
    }

    @Override
    public OnboardingProcess cancelRegistration(String clientId, String externalId) {
        var process = getRegistration(clientId, externalId);
        // The check and the transition are ONE atomic store operation: a cancellation can neither
        // relabel an outcome that lands concurrently (the orchestrator drives the flow inside the
        // submitting call) nor be resurrected by a racing step-save — the stores also refuse to
        // overwrite a terminal state.
        if (!repository.cancel(process.id(), "Cancelled by the onboarding service provider")) {
            var current = repository.findById(process.id()).orElseThrow(() -> notFound(externalId));
            throw new CancellationNotAllowedException(externalId, current.state());
        }
        var cancelled = repository.findById(process.id()).orElseThrow(() -> notFound(externalId));
        log.info("Onboarding {} cancelled by its submitter (externalId={})", cancelled.id(), externalId);
        // Terminal like any other off-ramp: subscribers waiting on this onboarding must learn it
        // is over. Deliberately NO status callback — the OSP initiated the cancellation, and the
        // synchronous 204 is its acknowledgment.
        eventPublisher.onboardingCompleted(new OnboardingCompleted(cancelled.id(), cancelled.externalId(),
                cancelled.bpn(), cancelled.holderId(), cancelled.state(), cancelled.failureReason()));
        return cancelled;
    }

    /**
     * The deterministic pick when (clientId, externalId) matches several attempts (legacy
     * resubmissions, re-registration after a cancellation): the one still in flight beats any
     * terminal one, a completed attempt beats a declined/failed/cancelled one, and remaining ties
     * break on the process id — stable, never an arbitrary row.
     */
    private static Optional<OnboardingProcess> select(List<OnboardingProcess> candidates) {
        return candidates.stream().max(
                Comparator.comparingInt(DefaultNetworkService::recoveryPrecedence)
                        .thenComparing(OnboardingProcess::id));
    }

    private static int recoveryPrecedence(OnboardingProcess process) {
        if (!process.isTerminal()) {
            return 2;
        }
        return process.state() == OnboardingState.COMPLETED ? 1 : 0;
    }

    private static NoSuchElementException notFound(String externalId) {
        // Foreign and unknown externalIds read identically — the message must not reveal whether
        // another OSP holds the id.
        return new NoSuchElementException("No registration with externalId '%s' for this client".formatted(externalId));
    }
}
