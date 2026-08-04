package com.metaform.cxve.application;

import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.ProvisionedParticipant;
import com.metaform.cxve.domain.port.BusinessPartnerNumberService;
import com.metaform.cxve.domain.port.CredentialIssuanceService;
import com.metaform.cxve.domain.port.IdentityProofingService;
import com.metaform.cxve.domain.port.OnboardingRepository;
import com.metaform.cxve.domain.port.RegistrationValidationService;
import com.metaform.cxve.domain.port.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Sequences the CX-0006 onboarding steps. Holds no state of its own — all persistence goes through
 * {@link OnboardingRepository}, so the in-memory store can be swapped for a durable one without
 * touching this business logic.
 *
 * <p>{@link #start} drives the process as far as it can go synchronously; it stops at an async gate
 * (identity proofing, participant provisioning) and is resumed by a later {@link #advance} /
 * {@link #advanceByHolder} call — e.g. from a proofing callback or a NATS issuance event.
 */
@Service
public class OnboardingOrchestratorImpl implements OnboardingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OnboardingOrchestratorImpl.class);

    // Exponential backoff for polling the async participant provisioning result (context ID + holder PID):
    // start at 1.5 s, double each attempt up to 8 s, give up after 8 polls.
    private static final long INITIAL_BACKOFF_MILLIS = 1000;
    private static final long MAX_BACKOFF_MILLIS = 8_000;
    private static final int MAX_PROVISION_POLLS = 8;

    private final RegistrationValidationService validationService;
    private final BusinessPartnerNumberService bpnService;
    private final IdentityProofingService identityProofingService;
    private final WalletService walletService;
    private final CredentialIssuanceService credentialIssuanceService;
    private final OnboardingRepository repository;
    private final RegistrationStatusService registrationStatusService;

    public OnboardingOrchestratorImpl(RegistrationValidationService validationService,
                                      BusinessPartnerNumberService bpnService,
                                      IdentityProofingService identityProofingService,
                                      WalletService walletService,
                                      CredentialIssuanceService credentialIssuanceService,
                                      OnboardingRepository repository,
                                      RegistrationStatusService registrationStatusService) {
        this.validationService = validationService;
        this.bpnService = bpnService;
        this.identityProofingService = identityProofingService;
        this.walletService = walletService;
        this.credentialIssuanceService = credentialIssuanceService;
        this.repository = repository;
        this.registrationStatusService = registrationStatusService;
    }

    @Override
    public String start(PartnerRegistrationData registrationData) {
        var id = UUID.randomUUID().toString();
        var process = OnboardingProcess.submitted(id, registrationData.externalId());
        repository.create(process, registrationData);
        log.info("Starting onboarding process ID '{}' for participant \"{}\")", id, registrationData.name());
        processOnboarding(id);
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
            case IDENTITY_VERIFIED -> provisionParticipant(process, payload);
            case WALLET_PROVISIONED -> issueCredentials(process);
            case CREDENTIALS_ISSUED -> process.withState(OnboardingState.COMPLETED);
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

    @Override
    public void linkHolder(String processId, String holderId) {
        var process = get(processId).withHolderId(holderId);
        repository.save(process);
        log.info("Linked holder '{}' to onboarding [{}]", holderId, processId);
    }

    @Override
    public Optional<OnboardingProcess> advanceByHolder(String holderId) {
        var process = repository.findByHolderId(holderId);
        if (process.isEmpty()) {
            log.warn("Received issuance event for unknown holder {} — no linked onboarding", holderId);
            return Optional.empty();
        }
        return Optional.of(processOnboarding(process.get().id()));
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
     * Logs the result of a single {@link #advance} call: state transitions at debug, terminal
     * outcomes at info (or warn for rejection).
     */
    private void processOutcome(OnboardingProcess before, OnboardingProcess after) {
        if (before.state() == after.state()) {
            return;
        }
        switch (after.state()) {
            case COMPLETED -> {
                log.info("Onboarding {} completed (bpn={}, wallet={})",
                        after.id(), after.bpn(), after.participantProfileId());
                 registrationStatusService.invokeCallback();
            }
            case REJECTED -> log.warn("Onboarding {} rejected: {}", after.id(), after.failureReason());
            case FAILED -> log.error("Onboarding {} failed: {}", after.id(), after.failureReason());
            default -> log.debug("Onboarding {} transitioned {} -> {}",
                    after.id(), before.state(), after.state());
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

    private OnboardingProcess provisionParticipant(OnboardingProcess process, PartnerRegistrationData payload) {
        ProvisionedParticipant participant;
        if (process.participantProfileId() != null) { // participant deployment already in process, only need to check status
            participant = walletService.checkProvisionStatus(process);

            // Poll for the async provisioning result with exponential backoff — capped delay, bounded
            // attempts — instead of a fixed-interval wait. If it isn't ready within the budget, leave
            // the process at this gate so a later advance (e.g. an issuance event) retries from scratch.
            var backoffMillis = INITIAL_BACKOFF_MILLIS;
            var attempt = 0;
            while (participant.participantContextId() == null || participant.holderProcessId() == null) {
                if (++attempt > MAX_PROVISION_POLLS) {
                    log.warn("Onboarding {}: participant context ID / holder PID still unassigned after {} polls; will retry later",
                            process.id(), MAX_PROVISION_POLLS);
                    return process;
                }
                log.debug("Onboarding {}: participant not ready, retrying in {} ms (attempt {}/{})",
                        process.id(), backoffMillis, attempt, MAX_PROVISION_POLLS);
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
                participant = walletService.checkProvisionStatus(process);
            }

            var participantContextId = participant.participantContextId();
            var holderPid = participant.holderProcessId();
            process = process.withParticipantContextId(participantContextId)
                    .withHolderProcessId(holderPid);
        } else {
            participant = walletService.provisionWallet(process, payload);
            linkHolder(process.id(), participant.identifier());
        }

        if (participant.error()) {
            return process.failed("Failed to deploy participant profile with ID '%s'".formatted(participant.id()));
        }
        return process.withParticipantProfile(participant.id())
                .withHolderId(participant.identifier())
                .withTenantId(participant.tenantId());
    }

    private OnboardingProcess issueCredentials(OnboardingProcess process) {
        if (!credentialIssuanceService.issueBpnCredential(process)) {
            return process.failed("BPN Credential issuance failed");
        }
        if (!credentialIssuanceService.issueFrameworkAgreementCredential(process)) {
            return process.failed("Framework Agreement Credential issuance failed");
        }
        if (!credentialIssuanceService.issueMembershipCredential(process)) {
            return process.failed("Membership Credential issuance failed");
        }
        return process.withState(OnboardingState.CREDENTIALS_ISSUED);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting participant provisioning", e);
        }
    }
}
