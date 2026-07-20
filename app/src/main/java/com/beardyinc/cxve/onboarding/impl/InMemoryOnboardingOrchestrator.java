package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.BusinessPartnerNumberService;
import com.beardyinc.cxve.onboarding.CredentialIssuanceService;
import com.beardyinc.cxve.onboarding.IdentityProofingService;
import com.beardyinc.cxve.onboarding.OnboardingOrchestrator;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import com.beardyinc.cxve.onboarding.OnboardingState;
import com.beardyinc.cxve.onboarding.RegistrationValidationService;
import com.beardyinc.cxve.onboarding.WalletService;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
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

    private final RegistrationValidationService validationService;
    private final BusinessPartnerNumberService bpnService;
    private final IdentityProofingService identityProofingService;
    private final WalletService walletService;
    private final CredentialIssuanceService credentialIssuanceService;

    private final ConcurrentHashMap<UUID, OnboardingProcess> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PartnerRegistrationData> payloads = new ConcurrentHashMap<>();

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
    public UUID start(PartnerRegistrationData registrationData) {
        var id = UUID.randomUUID();
        var process = OnboardingProcess.submitted(id, registrationData.externalId());
        processes.put(id, process);
        payloads.put(id, registrationData);
        drive(id);
        return id;
    }

    @Override
    public OnboardingProcess advance(UUID processId) {
        var process = get(processId);
        if (process.isTerminal()) {
            return process;
        }
        var payload = payloads.get(processId);
        var next = switch (process.state()) {
            case SUBMITTED -> validate(process, payload);
            case VALIDATED -> assignBpn(process, payload);
            case BPN_ASSIGNED -> proveIdentity(process);
            case IDENTITY_VERIFIED -> provisionWallet(process, payload);
            case WALLET_PROVISIONED -> issueCredentials(process);
            case CREDENTIALS_ISSUED -> process.withState(OnboardingState.COMPLETED);
            case COMPLETED, REJECTED, FAILED -> process;
        };
        processes.put(processId, next);
        return next;
    }

    @Override
    public OnboardingProcess get(UUID processId) {
        var process = processes.get(processId);
        if (process == null) {
            throw new NoSuchElementException("No onboarding process with id " + processId);
        }
        return process;
    }

    /**
     * Advances repeatedly until the process is terminal or a step makes no progress (an async gate
     * not yet satisfied), returning the resulting process.
     */
    private OnboardingProcess drive(UUID id) {
        var process = get(id);
        while (!process.isTerminal()) {
            var before = process.state();
            process = advance(id);
            if (process.state() == before) {
                break;
            }
        }
        return process;
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

    private OnboardingProcess provisionWallet(OnboardingProcess process, PartnerRegistrationData payload) {
        return process.withWallet(walletService.provisionWallet(process, payload));
    }

    private OnboardingProcess issueCredentials(OnboardingProcess process) {
        credentialIssuanceService.issueBpnCredential(process);
        credentialIssuanceService.issueFrameworkAgreementCredential(process);
        credentialIssuanceService.issueMembershipCredential(process);
        return process.withState(OnboardingState.CREDENTIALS_ISSUED);
    }
}
