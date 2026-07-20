package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.BusinessPartnerNumberService;
import com.beardyinc.cxve.onboarding.CredentialIssuanceService;
import com.beardyinc.cxve.onboarding.IdentityProofingService;
import com.beardyinc.cxve.onboarding.OnboardingOrchestrator;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import com.beardyinc.cxve.onboarding.OnboardingState;
import com.beardyinc.cxve.onboarding.RegistrationValidationService;
import com.beardyinc.cxve.onboarding.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link OnboardingOrchestrator} that sequences the CX-0006 steps. Process state and the
 * originating registration payload are held in maps — fine for a stub, to be replaced by a persistent
 * store and a durable, event-driven progression.
 *
 * <p>{@link #start} drives the process as far as it can go synchronously; it stops at the identity
 * proofing gate if proofing has not yet reported success, and would be resumed by a later
 * {@link #advance} call (e.g. from a proofing callback).
 */
@Service
public class InMemoryOnboardingOrchestrator implements OnboardingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOnboardingOrchestrator.class);

    // Exponential backoff for polling the async participant provisioning result (context ID + holder PID):
    // start at 500 ms, double each attempt up to 8 s, give up after 8 polls (~40 s total).
    private static final long INITIAL_BACKOFF_MILLIS = 500;
    private static final long MAX_BACKOFF_MILLIS = 8_000;
    private static final int MAX_PROVISION_POLLS = 8;

    private final RegistrationValidationService validationService;
    private final BusinessPartnerNumberService bpnService;
    private final IdentityProofingService identityProofingService;
    private final WalletService walletService;
    private final CredentialIssuanceService credentialIssuanceService;

    private final ConcurrentHashMap<String, OnboardingProcess> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PartnerRegistrationData> payloads = new ConcurrentHashMap<>();
    // holderId -> processId, so issuance events arriving over NATS can be correlated back to a process.
    private final ConcurrentHashMap<String, String> holderIndex = new ConcurrentHashMap<>();

    public InMemoryOnboardingOrchestrator(RegistrationValidationService validationService,
                                          BusinessPartnerNumberService bpnService,
                                          IdentityProofingService identityProofingService,
                                          WalletService walletService,
                                          CredentialIssuanceService credentialIssuanceService) {
        this.validationService = validationService;
        this.bpnService = bpnService;
        this.identityProofingService = identityProofingService;
        this.walletService = walletService;
        this.credentialIssuanceService = credentialIssuanceService;
    }

    @Override
    public String start(PartnerRegistrationData registrationData) {
        var id = UUID.randomUUID().toString();
        var process = OnboardingProcess.submitted(id, registrationData.externalId());
        processes.put(id, process);
        payloads.put(id, registrationData);
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
        var payload = payloads.get(processId);
        var next = switch (process.state()) {
            case SUBMITTED -> validate(process, payload);
            case VALIDATED -> assignBpn(process, payload);
            case BPN_ASSIGNED -> proveIdentity(process);
            case IDENTITY_VERIFIED -> provisionParticipant(process, payload);
            case WALLET_PROVISIONED -> issueCredentials(process);
            case CREDENTIALS_ISSUED -> process.withState(OnboardingState.COMPLETED);
            case COMPLETED, REJECTED, FAILED -> process;
        };
        processes.put(processId, next);
        logOutcome(process, next);
        return next;
    }

    @Override
    public OnboardingProcess get(String processId) {
        var process = processes.get(processId);
        if (process == null) {
            throw new NoSuchElementException("No onboarding process with id " + processId);
        }
        return process;
    }

    @Override
    public void linkHolder(String processId, String holderId) {
        processes.computeIfPresent(processId, (key, process) -> process.withHolderId(holderId));
        holderIndex.put(holderId, processId);
        log.info("Linked holder '{}' to onboarding [{}]", holderId, processId);
    }

    @Override
    public Optional<OnboardingProcess> advanceByHolder(String holderId) {
        var processId = holderIndex.get(holderId);
        if (processId == null) {
            log.warn("Received issuance event for unknown holder {} — no linked onboarding", holderId);
            return Optional.empty();
        }
        return Optional.of(processOnboarding(processId));
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
    private void logOutcome(OnboardingProcess before, OnboardingProcess after) {
        if (before.state() == after.state()) {
            return;
        }
        switch (after.state()) {
            case COMPLETED -> log.info("Onboarding {} completed (bpn={}, wallet={})",
                    after.id(), after.bpn(), after.participantProfileId());
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

        ParticipantProfile participantProfile;
        if (process.participantProfileId() != null) { // participant deployment already in process, only need to check status
            participantProfile = walletService.checkProvisionStatus(process);

            // Poll for the async provisioning result with exponential backoff — capped delay, bounded
            // attempts — instead of a fixed-interval wait. If it isn't ready within the budget, leave
            // the process at this gate so a later advance (e.g. an issuance event) retries from scratch.
            var backoffMillis = INITIAL_BACKOFF_MILLIS;
            var attempt = 0;
            while (participantProfile.getParticipantContextId() == null || participantProfile.getHolderProcessId() == null) {
                if (++attempt > MAX_PROVISION_POLLS) {
                    log.warn("Onboarding {}: participant context ID / holder PID still unassigned after {} polls; will retry later",
                            process.id(), MAX_PROVISION_POLLS);
                    return process;
                }
                log.debug("Onboarding {}: participant not ready, retrying in {} ms (attempt {}/{})",
                        process.id(), backoffMillis, attempt, MAX_PROVISION_POLLS);
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
                participantProfile = walletService.checkProvisionStatus(process);
            }

            var participantContextId = participantProfile.getParticipantContextId();
            var holderPid = participantProfile.getHolderProcessId();
            log.info("Onboarding {}: participant context ID: {}, holder PID: {}", process.id(), participantContextId, holderPid);
            process = process.withParticipantContextId(participantContextId)
                    .withHolderProcessId(holderPid);
        } else {
            participantProfile = walletService.provisionWallet(process, payload);
            linkHolder(process.id(), participantProfile.getIdentifier());
        }


        if (participantProfile.isError()) {
            return process.failed("Failed to deploy participant profile with ID '%s'".formatted(participantProfile.getId()));
        }
        return process.withParticipantProfile(participantProfile.getId())
                .withHolderId(participantProfile.getIdentifier())
                .withTenantId(participantProfile.getTenantId());
//
//        var holderProcessId = participantProfile.getHolderProcessId();
//        if (holderProcessId != null) {
//            log.debug("holder PID present, participant ready for issuance");
//            return process.withHolderProcessId(holderProcessId);
//        } else {
//            log.warn("holder PID not ppresent, participant not ready for issuance");
//        }

    }

    private OnboardingProcess issueCredentials(OnboardingProcess process) {
        var isBpnIssued = credentialIssuanceService.issueBpnCredential(process);
        var isFwIssued = credentialIssuanceService.issueFrameworkAgreementCredential(process);
        var isMembershipIssued = credentialIssuanceService.issueMembershipCredential(process);
        if (isBpnIssued && isFwIssued && isMembershipIssued) {
            return process.withState(OnboardingState.CREDENTIALS_ISSUED);
        }
        return process; // no state change
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
