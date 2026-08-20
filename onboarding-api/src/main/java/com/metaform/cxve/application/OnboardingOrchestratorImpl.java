package com.metaform.cxve.application;

import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingStarted;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.BusinessPartnerNumberService;
import com.metaform.cxve.domain.port.HolderRegistrationService;
import com.metaform.cxve.domain.port.IdentityProofingService;
import com.metaform.cxve.domain.port.OnboardingEventPublisher;
import com.metaform.cxve.domain.port.OnboardingRepository;
import com.metaform.cxve.domain.port.RegistrationValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Sequences the CX-0006 onboarding steps. Holds no state of its own — all persistence goes through
 * {@link OnboardingRepository}, so the in-memory store can be swapped for a durable one without
 * touching this business logic.
 *
 * <p>{@link #start} drives the process as far as it can go synchronously; the one async gate left
 * is identity proofing, where it stops until a later {@link #advance} call resumes it — e.g. from
 * a proofing callback. With proofing satisfied, the drive runs straight through holder
 * registration to completion, so the CONFIRMED status callback fires within the submitting call.
 */
@Service
public class OnboardingOrchestratorImpl implements OnboardingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OnboardingOrchestratorImpl.class);

    private final RegistrationValidationService validationService;
    private final BusinessPartnerNumberService bpnService;
    private final IdentityProofingService identityProofingService;
    private final HolderRegistrationService holderRegistrationService;
    private final OnboardingRepository repository;
    private final RegistrationStatusService registrationStatusService;
    private final OnboardingEventPublisher eventPublisher;
    private final ParticipantDidResolver didResolver;

    public OnboardingOrchestratorImpl(RegistrationValidationService validationService,
                                      BusinessPartnerNumberService bpnService,
                                      IdentityProofingService identityProofingService,
                                      HolderRegistrationService holderRegistrationService,
                                      OnboardingRepository repository,
                                      RegistrationStatusService registrationStatusService,
                                      OnboardingEventPublisher eventPublisher,
                                      ParticipantDidResolver didResolver) {
        this.validationService = validationService;
        this.bpnService = bpnService;
        this.identityProofingService = identityProofingService;
        this.holderRegistrationService = holderRegistrationService;
        this.repository = repository;
        this.registrationStatusService = registrationStatusService;
        this.eventPublisher = eventPublisher;
        this.didResolver = didResolver;
    }

    @Override
    public String start(String clientId, PartnerRegistrationData registrationData) {
        var id = UUID.randomUUID().toString();
        // The process is authoritative for both identities from here on. The DID is final at
        // submission (resolved by the same rule the holder registration will use). The BPN is final
        // only when the registration supplied one (resolveOrCreate keeps it verbatim); when it did
        // not, the seed is null and the BPN step assigns one — subscribers get it via the completed
        // event. The client id is final too: the token identity of the submitter, recorded so
        // status callbacks route to the provider this registration belongs to.
        var did = didResolver.resolve(registrationData);
        var process = OnboardingProcess.submitted(id, registrationData.externalId(), registrationData.bpn(), did, clientId);
        repository.create(process, registrationData);
        log.info("Starting onboarding process ID '{}' for participant \"{}\" (submitted by client '{}')",
                id, registrationData.name(), clientId);
        // Announced BEFORE the process is driven: processOnboarding() runs the flow synchronously as
        // far as it can go, so publishing afterwards would order "started" after the work it starts.
        eventPublisher.onboardingStarted(new OnboardingStarted(
                id, registrationData.externalId(), registrationData.bpn(), did));
        try {
            processOnboarding(id);
        } catch (RuntimeException e) {
            // A throw here has no second chance: nothing but a proofing callback ever re-drives
            // advance(), and only up to its own gate. Left alone the process would sit non-terminal
            // forever, which isActiveRegistration() reads as in flight, so the partner could never
            // re-register either. Record and announce the failure, then let the caller see the
            // error.
            recordFailure(id, e);
            throw e;
        }
        return id;
    }

    @Override
    public OnboardingProcess advance(String processId) {
        var process = get(processId);
        if (process.isTerminal()) {
            return process;
        }
        var payload = repository.findPayload(processId).orElse(null);
        var next = switch (process.state()) {
            case SUBMITTED -> validate(process, payload);
            case VALIDATED -> assignBpn(process, payload);
            case BPN_ASSIGNED -> proveIdentity(process);
            case IDENTITY_VERIFIED -> registerHolder(process, payload);
            // CREDENTIALS_ISSUED is no longer entered (credentials are issued downstream, after
            // the EDC resources exist) but remains a valid stored state that must keep advancing.
            case WALLET_PROVISIONED, CREDENTIALS_ISSUED -> process.withState(OnboardingState.COMPLETED);
            case COMPLETED, REJECTED, FAILED -> process;
        };
        repository.save(next);
        processOutcome(process, next);
        return next;
    }

    @Override
    public OnboardingProcess get(String processId) {
        return repository.findById(processId)
                .orElseThrow(() -> new NoSuchElementException("No onboarding process with id " + processId));
    }

    /**
     * Advances repeatedly until the process is terminal or a step makes no progress (an async gate
     * not yet satisfied), returning the resulting process.
     */
    private OnboardingProcess processOnboarding(String id) {
        var process = get(id);
        while (!process.isTerminal()) {
            var before = process.state();
            process = advance(id);
            if (process.state() == before) {
                log.info("Onboarding {} paused at {} awaiting async completion", id, process.state());
                break;
            }
        }
        return process;
    }

    /**
     * Drives an onboarding that died on an exception to {@link OnboardingState#FAILED}, so it is
     * reported through the same path as a declared failure.
     */
    private void recordFailure(String processId, RuntimeException cause) {
        var process = get(processId);
        if (process.isTerminal()) {
            // The outcome is already recorded and announced and the exception came from something
            // afterwards (the status callback, say). Overwriting it would turn a completed onboarding
            // into a failed one and announce it twice.
            log.error("Onboarding {} raised an error after reaching {}", processId, process.state(), cause);
            return;
        }
        // Logged with the cause here — processOutcome reports the outcome, not the stack trace.
        log.error("Onboarding {} raised an error at {}", processId, process.state(), cause);
        var failed = process.failed("Failed at %s: %s".formatted(process.state(), cause.getMessage()));
        repository.save(failed);
        processOutcome(process, failed);
    }

    /**
     * Reports the result of a single {@link #advance} call: intermediate transitions at debug, and
     * every terminal outcome — completed, rejected or failed alike — as an
     * {@link OnboardingCompleted} event.
     *
     * <p>The announcement is keyed off {@link OnboardingProcess#isTerminal()} rather than off the
     * individual states, so a terminal state added later cannot end an onboarding silently and leave
     * a subscriber waiting on a process that is already over.
     */
    private void processOutcome(OnboardingProcess before, OnboardingProcess after) {
        if (before.state() == after.state()) {
            return;
        }
        if (!after.isTerminal()) {
            log.debug("Onboarding {} transitioned {} -> {}", after.id(), before.state(), after.state());
            return;
        }
        switch (after.state()) {
            case COMPLETED -> log.info("Onboarding {} completed (bpn={}, holder DID={})",
                    after.id(), after.bpn(), after.holderId());
            case REJECTED -> log.warn("Onboarding {} rejected: {}", after.id(), after.failureReason());
            default -> log.error("Onboarding {} failed: {}", after.id(), after.failureReason());
        }
        // Announced ahead of the status callback: that callback is an outbound call to a third party,
        // and the outcome must reach subscribers whether or not that party is reachable.
        eventPublisher.onboardingCompleted(new OnboardingCompleted(after.id(), after.externalId(),
                after.bpn(), after.holderId(), after.state(), after.failureReason()));
        if (after.state() == OnboardingState.COMPLETED) {
            registrationStatusService.invokeCallback(after);
        }
    }

    private OnboardingProcess validate(OnboardingProcess process, PartnerRegistrationData payload) {
        var result = validationService.validate(payload);
        return result.valid()
                ? process.withState(OnboardingState.VALIDATED)
                : process.rejected(String.join("; ", result.violations()));
    }

    private OnboardingProcess assignBpn(OnboardingProcess process, PartnerRegistrationData payload) {
        return process.withBpn(bpnService.resolveOrCreate(payload));
    }

    private OnboardingProcess proveIdentity(OnboardingProcess process) {
        var reference = identityProofingService.initiateProofing(process);
        // Async gate: stay in BPN_ASSIGNED until proofing reports success; a callback re-drives advance().
        return identityProofingService.isVerified(reference)
                ? process.withState(OnboardingState.IDENTITY_VERIFIED)
                : process;
    }

    private OnboardingProcess registerHolder(OnboardingProcess process, PartnerRegistrationData payload) {
        holderRegistrationService.registerHolder(process, payload);
        // The state name predates the split: what exists at this point is the holder entry in the
        // IssuerService, not a provisioned wallet. Kept because the enum is part of the stored and
        // announced contract.
        return process.withState(OnboardingState.WALLET_PROVISIONED);
    }
}
