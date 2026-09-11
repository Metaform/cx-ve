package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.MembershipRepository;
import com.metaform.cxve.hub.domain.port.OnboardingApi;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
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
 * Drives a membership's two legs ASYNCHRONOUSLY, each from the thread that carries its
 * triggering signal: {@link #onboard} persists the record and submits the registration — and
 * that is ALL it does; the outcome arrives exclusively through the Onboarding API's status
 * callback ({@link #onRegistrationStatus}), whose CONFIRMED triggers the EDC provisioning on a
 * background worker (the stored request payload supplies the agreements). No assumption is made
 * about WHEN the callback arrives — within the submitting call (how the current Onboarding API
 * behaves), later, or redelivered.
 *
 * <p>Concurrent writers — the submitting thread recording the process id, the callback, the
 * provisioning worker, a {@link #get} refresh — are serialized by two mechanisms working
 * together: every write is an optimistic-lock compare-and-swap retried on a fresh snapshot
 * ({@link #update}), so no writer ever overwrites another's fields; and state changes go through
 * the monotonic transition table ({@link MembershipState#canAdvanceTo}), so a late or duplicate
 * signal is ignored instead of moving a record backwards. Provisioning runs AT MOST ONCE per
 * membership: the CONFIRMED→PROVISIONING claim is the gate, and only the writer that wins it
 * deploys — which also makes duplicate callbacks the recovery path for a confirmation whose
 * provisioning never started (e.g. a crash in between), and holds across replicas because the
 * version check is in the database.
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);
    private static final int MAX_SAVE_ATTEMPTS = 5;

    private final MembershipRepository repository;
    private final OnboardingApi onboardingApi;
    private final TenantManager tenantManager;
    private final String didTemplate;
    private final Executor provisioningExecutor;

    public MembershipService(MembershipRepository repository,
                             OnboardingApi onboardingApi,
                             TenantManager tenantManager,
                             @Value("${participant.did.template:did:web:identity.cxve.localhost:}") String didTemplate,
                             @Qualifier("provisioningExecutor") Executor provisioningExecutor) {
        this.repository = repository;
        this.onboardingApi = onboardingApi;
        this.tenantManager = tenantManager;
        this.didTemplate = didTemplate;
        this.provisioningExecutor = provisioningExecutor;
    }

    /**
     * Creates the membership and submits its registration, then returns — typically in
     * SUBMITTED; provisioning is driven entirely by the status callback. The DID is resolved
     * here — caller-supplied or template-derived, the SAME rule the Onboarding API applies — and
     * passed explicitly with the registration, so the identity the holder is registered under
     * and the identity the profile is deployed as cannot drift. The record is persisted BEFORE
     * the submission because the callback can arrive on another thread while the submitting call
     * is still on the wire — the handler must find the record (and may well have advanced it by
     * the time this method returns; the process id is recorded under the compare-and-swap either
     * way).
     */
    public Membership onboard(MemberData data) {
        var externalId = UUID.randomUUID().toString();
        var did = ofNullable(data.did()).orElseGet(() -> didTemplate + data.shortName());
        var membership = Membership.submitted(externalId, data.name(), did, data.bpn());
        repository.create(membership, data);
        log.info("Starting membership '{}' for participant \"{}\" (did={})", externalId, data.name(), did);
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
     * Reacts to an Onboarding API status callback — the ONLY driver of the registration
     * outcome. CONFIRMED advances the record and triggers provisioning on the background
     * worker; a redelivered CONFIRMED against a record that is confirmed but unclaimed
     * re-triggers (that is the healing path), while against anything further along it is
     * ignored. DECLINED terminally rejects a not-yet-confirmed record (the internal REJECTED
     * state — the wire value changed with the spec, the persisted enum did not); after a
     * confirmation it is contradictory input and ignored.
     */
    public Membership onRegistrationStatus(String externalId, String status, String message) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "CONFIRMED" -> {
                var membership = update(externalId, current ->
                        current.state().canAdvanceTo(MembershipState.CONFIRMED)
                                ? current.withState(MembershipState.CONFIRMED)
                                : current);
                if (membership.state() == MembershipState.CONFIRMED) {
                    provisioningExecutor.execute(() -> provision(externalId));
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
     * The provisioning leg, on the background worker: claim, deploy, record. Never throws —
     * failures land on the record.
     */
    private void provision(String externalId) {
        try {
            var claimed = claim(externalId);
            if (claimed.isEmpty()) {
                log.debug("Membership '{}' was claimed elsewhere or has moved on — nothing to provision", externalId);
                return;
            }
            var membership = claimed.get();
            var payload = repository.findPayload(externalId).orElse(null);
            if (payload == null) {
                update(externalId, current -> current.state().canAdvanceTo(MembershipState.FAILED)
                        ? current.failed("Provisioning failed: the stored membership payload is missing")
                        : current);
                return;
            }
            var activeAgreements = payload.agreements().stream()
                    .filter(MemberData.AgreementConsent::hasActiveConsent)
                    .map(MemberData.AgreementConsent::agreementId)
                    .toList();
            log.info("Membership '{}' confirmed — provisioning EDC resources for did={}", externalId, membership.did());
            TenantManager.ProvisionedProfile profile;
            try {
                profile = tenantManager.deployParticipant(membership, activeAgreements);
            } catch (RuntimeException e) {
                log.error("Membership '{}' failed to provision", externalId, e);
                update(externalId, current -> current.state().canAdvanceTo(MembershipState.FAILED)
                        ? current.failed("Provisioning failed: " + e.getMessage())
                        : current);
                return;
            }
            update(externalId, current -> applyProfile(
                    current.provisioning(profile.tenantId(), profile.participantProfileId()), profile));
        } catch (RuntimeException e) {
            // the worker must never die silently — but there is also no caller to throw to
            log.error("Membership '{}': provisioning aborted unexpectedly", externalId, e);
        }
    }

    /**
     * The at-most-once gate: CONFIRMED→PROVISIONING as a compare-and-swap. Exactly one writer
     * wins it per membership — a concurrent duplicate (redelivered callback, second replica)
     * finds the state already moved and backs off with empty.
     */
    private Optional<Membership> claim(String externalId) {
        for (var attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++) {
            var current = current(externalId);
            if (current.state() != MembershipState.CONFIRMED) {
                return Optional.empty();
            }
            var next = current.withState(MembershipState.PROVISIONING);
            try {
                repository.save(next);
                return Optional.of(next);
            } catch (OptimisticLockingFailureException e) {
                // another writer moved the record — re-read and re-decide
            }
        }
        throw new IllegalStateException(
                "Membership '%s' could not be claimed for provisioning after %d attempts".formatted(externalId, MAX_SAVE_ATTEMPTS));
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
