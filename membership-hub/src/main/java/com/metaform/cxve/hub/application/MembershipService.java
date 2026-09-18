package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.MembershipRepository;
import com.metaform.cxve.hub.domain.port.OnboardingApi;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import static java.util.Optional.ofNullable;

/**
 * Drives a membership in the order the credential offer dictates: DEPLOY FIRST, REGISTER SECOND.
 * The Onboarding API registers the member as a credential holder and has the IssuerService push a
 * DCP offer to the Credential Service the member's DID document advertises — so the member's
 * wallet must exist before the registration is submitted. A member this environment hosts is
 * therefore provisioned through the CFM Tenant Manager first, exactly as an external vendor has
 * already provisioned itself, and only then registered; a member that brought its own DID is
 * registered straight away.
 *
 * <p>That splits {@link #onboard} in two. A member with its own DID is submitted INLINE, so a bad
 * registration fails in the caller's request. A member hosted here is persisted in PROVISIONING
 * and handed to the {@code provisioningExecutor}, which deploys, waits for the participant context
 * and then submits — the call returns as soon as the deployment is under way. Either way the
 * outcome arrives through the Onboarding API's status callback
 * ({@link #onRegistrationStatus}), whose confirmation is the membership's terminal success: the
 * credentials have been offered.
 *
 * <p>Concurrent writers — the submitting thread recording the process id, the callback, the
 * provisioning worker, a {@link #get} refresh — are serialized by two mechanisms working together:
 * every write is an optimistic-lock compare-and-swap retried on a fresh snapshot
 * ({@link #update}), so no writer ever overwrites another's fields; and state changes go through
 * the monotonic transition table ({@link MembershipState#canAdvanceTo}), so a late or duplicate
 * signal is ignored instead of moving a record backwards.
 *
 * <p>The provisioning leg runs at most once because only the creating call triggers it — there is
 * no redelivered signal that could start a second one. The flip side is that a hub that dies
 * mid-deployment leaves the record in PROVISIONING with nothing to resume it; the deployment is
 * still readable through {@link #get}, but the registration will not be submitted.
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);
    private static final int MAX_SAVE_ATTEMPTS = 5;

    private final MembershipRepository repository;
    private final OnboardingApi onboardingApi;
    private final TenantManager tenantManager;
    private final String didTemplate;
    private final Duration provisioningTimeout;
    private final Duration provisioningPollInterval;
    private final Executor provisioningExecutor;

    public MembershipService(MembershipRepository repository,
                             OnboardingApi onboardingApi,
                             TenantManager tenantManager,
                             @Value("${participant.did.template:did:web:identity.cxve.localhost:}") String didTemplate,
                             @Value("${participant.provisioning.timeout:10m}") Duration provisioningTimeout,
                             @Value("${participant.provisioning.poll-interval:5s}") Duration provisioningPollInterval,
                             @Qualifier("provisioningExecutor") Executor provisioningExecutor) {
        this.repository = repository;
        this.onboardingApi = onboardingApi;
        this.tenantManager = tenantManager;
        this.didTemplate = didTemplate;
        this.provisioningTimeout = provisioningTimeout;
        this.provisioningPollInterval = provisioningPollInterval;
        this.provisioningExecutor = provisioningExecutor;
    }

    /**
     * Creates the membership and starts it. The DID is resolved here — caller-supplied or
     * template-derived, the SAME rule the Onboarding API applies — and passed explicitly with the
     * registration, so the identity the holder is registered under and the identity the profile is
     * deployed as cannot drift.
     *
     * <p>A member hosted here returns in PROVISIONING, its deployment running on the worker; one
     * that brought its own DID returns from the submitted registration, typically in SUBMITTED and
     * possibly already CREDENTIALS_OFFERED (the current Onboarding API calls back within the
     * submitting call). The record is persisted BEFORE the submission because the callback can
     * arrive on another thread while that call is still on the wire — the handler must find the
     * record.
     *
     * @throws DuplicateMembershipException when a live membership already holds this DID or BPN
     */
    public Membership onboard(MemberData data) {
        var did = ofNullable(data.did()).orElseGet(() -> didTemplate + data.shortName());
        rejectDuplicate(did, data.bpn());
        var externalId = UUID.randomUUID().toString();
        log.info("Starting membership '{}' for participant \"{}\" (did={})", externalId, data.name(), did);
        if (!data.hostedHere()) {
            repository.create(Membership.submitted(externalId, data.name(), did, data.bpn()), data);
            return register(externalId, did, data);
        }
        repository.create(Membership.provisioning(externalId, data.name(), did, data.bpn()), data);
        provisioningExecutor.execute(() -> deployAndRegister(externalId));
        // re-read rather than return the record as created: the worker may already have moved it
        return current(externalId);
    }

    /**
     * Refuses a member whose DID or BPN a live membership already holds. Registering the same DID
     * twice would be declined by the Onboarding API anyway — this check is what makes that a clean
     * 409 BEFORE anything is deployed, since a declined registration does NOT undo a deployment
     * that already happened. Dead attempts (rejected, failed) release their identities.
     */
    private void rejectDuplicate(String did, String bpn) {
        repository.findByDid(did).stream().filter(Membership::isLive).findFirst().ifPresent(existing -> {
            throw new DuplicateMembershipException("A membership for DID %s already exists: '%s' (%s)"
                    .formatted(did, existing.externalId(), existing.state()));
        });
        repository.findByBpn(bpn).stream().filter(Membership::isLive).findFirst().ifPresent(existing -> {
            throw new DuplicateMembershipException("A membership for BPN %s already exists: '%s' (%s)"
                    .formatted(bpn, existing.externalId(), existing.state()));
        });
    }

    /**
     * The hosted member's leg, on the background worker: deploy, wait for the participant context,
     * then submit the registration — by which time the member has a wallet the issuer's credential
     * offer can reach. Never throws; failures land on the record.
     */
    private void deployAndRegister(String externalId) {
        try {
            var payload = repository.findPayload(externalId).orElse(null);
            if (payload == null) {
                fail(externalId, "Provisioning failed: the stored membership payload is missing");
                return;
            }
            var membership = deployParticipant(current(externalId), payload);
            if (membership == null) {
                return;
            }
            var submitted = update(externalId, current -> current.state().canAdvanceTo(MembershipState.SUBMITTED)
                    ? current.withState(MembershipState.SUBMITTED)
                    : current);
            register(externalId, submitted.did(), payload);
        } catch (RuntimeException e) {
            // the worker must never die silently — but there is also no caller to throw to; the
            // record has already been failed by whichever step threw
            log.error("Membership '{}': onboarding aborted", externalId, e);
        }
    }

    /**
     * Submits the registration and records the process id. The Onboarding API does the rest of the
     * work this membership needs — assigning the BPN, registering the credential holder and having
     * the IssuerService offer it the dataspace's credentials — and reports the outcome through the
     * status callback, which may well arrive before this method returns.
     */
    private Membership register(String externalId, String did, MemberData data) {
        String processId;
        try {
            // Re-registered before every submission rather than once at startup: idempotent, and it
            // survives the callback store being reseeded underneath a long-running hub.
            onboardingApi.registerCallback();
            processId = onboardingApi.submitRegistration(externalId, did, data);
            log.info("Membership '{}' registered as onboarding process '{}'", externalId, processId);
        } catch (RuntimeException e) {
            log.error("Membership '{}' failed to submit its registration", externalId, e);
            // only from SUBMITTED: if a callback somehow advanced the record already (the
            // submission failed client-side but reached the server), its outcome stands
            update(externalId, current -> current.state() == MembershipState.SUBMITTED
                    ? current.failed("Registration submission failed: " + e.getMessage())
                    : current);
            throw e;
        }
        return update(externalId, current -> current.withOnboardingProcessId(processId));
    }

    /**
     * Reacts to an Onboarding API status callback — the ONLY driver of the registration outcome,
     * and now of the membership's terminal state: a confirmation means the holder is registered AND
     * its credentials offered, so the record goes straight to CREDENTIALS_OFFERED. A redelivered
     * confirmation against a record already there is ignored. DECLINED terminally rejects a
     * not-yet-confirmed record (the internal REJECTED state — the wire value changed with the spec,
     * the persisted enum did not); after a confirmation it is contradictory input and ignored.
     */
    public Membership onRegistrationStatus(String externalId, String status, String message) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "CONFIRMED" -> {
                var membership = update(externalId, current ->
                        current.state().canAdvanceTo(MembershipState.CREDENTIALS_OFFERED)
                                ? current.credentialsOffered()
                                : current);
                if (membership.state() == MembershipState.CREDENTIALS_OFFERED) {
                    log.info("Membership '{}' confirmed — its credentials have been offered (did={})",
                            externalId, membership.did());
                } else {
                    log.info("Membership '{}' is already {} — ignoring the CONFIRMED callback",
                            externalId, membership.state());
                }
                yield membership;
            }
            case "DECLINED" -> {
                var membership = update(externalId, current ->
                        current.state().canAdvanceTo(MembershipState.REJECTED)
                                ? current.rejected(message)
                                : current);
                if (membership.state() == MembershipState.REJECTED) {
                    log.warn("Membership '{}' was declined by the Onboarding API: {}", externalId, message);
                } else {
                    log.info("Membership '{}' is already {} — ignoring the DECLINED callback",
                            externalId, membership.state());
                }
                yield membership;
            }
            default -> {
                log.debug("Membership '{}' received status '{}' — nothing to do", externalId, status);
                yield current(externalId);
            }
        };
    }

    /**
     * All memberships registered under the given BPN — a plain repository read, deliberately
     * WITHOUT the Tenant Manager refresh {@link #get} performs: callers use this to rediscover
     * records (e.g. a permanent participant after a restart), not to poll provisioning progress.
     */
    public List<Membership> findByBpn(String bpn) {
        return repository.findByBpn(bpn);
    }

    /**
     * All memberships registered under the given DID, with the same read semantics as
     * {@link #findByBpn}. This is the lookup for an externally hosted member, whose DID is the
     * one identity its operator knows up front — and the one a repeat onboarding would collide
     * with. A caller about to onboard such a member looks here first and reuses what it finds.
     */
    public List<Membership> findByDid(String did) {
        return repository.findByDid(did);
    }

    /**
     * The membership by its external id. When a participant profile has been deployed for it,
     * its current state is read from the Tenant Manager (resolved via the stored profile id) —
     * that is where the participant context id appears and deployment errors surface.
     */
    public Membership get(String externalId) {
        var membership = current(externalId);
        if (membership.participantProfileId() == null || membership.isTerminal()) {
            return membership;
        }
        var profile = tenantManager.refresh(membership);
        return update(externalId, current -> applyProfile(current, profile));
    }

    /**
     * Deploys the member's EDC resources and waits for the participant context to appear — the
     * registration that follows has the issuer push a credential offer to the member's wallet, so
     * the wallet and the DID document must exist by then, and the Tenant Manager reports the
     * deployment's progress only when asked. Returns the PROVISIONED membership, or null when the
     * record was failed (the caller stops).
     */
    private Membership deployParticipant(Membership membership, MemberData payload) {
        var externalId = membership.externalId();
        var activeAgreements = payload.agreements().stream()
                .filter(MemberData.AgreementConsent::hasActiveConsent)
                .map(MemberData.AgreementConsent::agreementId)
                .toList();
        log.info("Membership '{}' — provisioning EDC resources for did={}", externalId, membership.did());
        TenantManager.ProvisionedProfile profile;
        try {
            profile = tenantManager.deployParticipant(membership, activeAgreements);
        } catch (RuntimeException e) {
            log.error("Membership '{}' failed to provision", externalId, e);
            return fail(externalId, "Provisioning failed: " + e.getMessage());
        }
        var deploying = update(externalId, current -> applyProfile(
                current.withProfile(profile.tenantId(), profile.participantProfileId()), profile));

        var deadline = System.nanoTime() + provisioningTimeout.toNanos();
        while (deploying.state() != MembershipState.PROVISIONED) {
            if (deploying.state() == MembershipState.FAILED) {
                // applyProfile saw the Tenant Manager report a deployment error
                return null;
            }
            if (System.nanoTime() > deadline) {
                log.error("Membership '{}' was not provisioned within {}", externalId, provisioningTimeout);
                return fail(externalId, "Provisioning did not complete within " + provisioningTimeout);
            }
            try {
                Thread.sleep(provisioningPollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Membership '{}': provisioning wait interrupted", externalId);
                return null;
            }
            try {
                var refreshed = tenantManager.refresh(deploying);
                deploying = update(externalId, current -> applyProfile(current, refreshed));
            } catch (RuntimeException e) {
                // a Tenant Manager blip is not the member's failure — keep waiting out the budget
                log.debug("Membership '{}': reading the participant profile failed, retrying", externalId, e);
            }
        }
        return deploying;
    }

    /** Fails the record (when the transition still allows it) and returns null for the caller. */
    private Membership fail(String externalId, String reason) {
        update(externalId, current -> current.state().canAdvanceTo(MembershipState.FAILED)
                ? current.failed(reason)
                : current);
        return null;
    }

    private Membership applyProfile(Membership membership, TenantManager.ProvisionedProfile profile) {
        if (profile.error()) {
            return membership.state().canAdvanceTo(MembershipState.FAILED)
                    ? membership.failed("Participant profile '%s' reported a deployment error"
                            .formatted(profile.participantProfileId()))
                    : membership;
        }
        if (profile.participantContextId() == null || !membership.state().canAdvanceTo(MembershipState.PROVISIONED)) {
            return membership;
        }
        log.info("Membership '{}' is provisioned (participant context '{}')",
                membership.externalId(), profile.participantContextId());
        return membership.withParticipantContextId(profile.participantContextId()).provisioned();
    }

    /**
     * Read-modify-write under the optimistic lock: {@code change} is applied to a FRESH snapshot
     * and retried on a conflict, so concurrent writers interleave instead of overwriting each
     * other. Returning the SAME instance signals a no-op (nothing is written) — which is how the
     * transition guards ignore late or duplicate signals.
     */
    private Membership update(String externalId, UnaryOperator<Membership> change) {
        OptimisticLockingFailureException conflict = null;
        for (var attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++) {
            var current = current(externalId);
            var next = change.apply(current);
            if (next == current) {
                return current;
            }
            try {
                repository.save(next);
                return next;
            } catch (OptimisticLockingFailureException e) {
                conflict = e;
            }
        }
        throw new IllegalStateException(
                "Membership '%s' could not be updated after %d attempts".formatted(externalId, MAX_SAVE_ATTEMPTS), conflict);
    }

    private Membership current(String externalId) {
        return repository.findByExternalId(externalId)
                .orElseThrow(() -> new NoSuchElementException("No membership with external id " + externalId));
    }
}
