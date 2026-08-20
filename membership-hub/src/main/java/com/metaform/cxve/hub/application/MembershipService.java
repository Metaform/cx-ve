package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.MembershipRepository;
import com.metaform.cxve.hub.domain.port.OnboardingApi;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.util.Optional.ofNullable;

/**
 * Sequences a membership's two legs INSIDE the submitting call: the registration is submitted to
 * the Onboarding API (which runs its flow synchronously and delivers the CONFIRMED status
 * callback before the submission returns), and once it is back, the participant profile is
 * deployed to the Tenant Manager — its id is stored on the membership record. There is no
 * polling: {@link #get} resolves the stored profile id and reads the profile's current state from
 * the Tenant Manager on every call, until the membership is terminal.
 *
 * <p>The record is persisted BEFORE the submission because the callback arrives on another thread
 * while the submitting call is still on the wire — the handler must find the record. After the
 * submission returns, the record is reloaded to pick up what the callback recorded: provisioning
 * proceeds only on a CONFIRMED registration — the Onboarding API answers a rejected registration
 * with a normal 200 as well, so the submission returning is deliberately not treated as consent.
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    private final MembershipRepository repository;
    private final OnboardingApi onboardingApi;
    private final TenantManager tenantManager;
    private final String didTemplate;

    public MembershipService(MembershipRepository repository,
                             OnboardingApi onboardingApi,
                             TenantManager tenantManager,
                             @Value("${participant.did.template:did:web:identity.cxve.localhost:}") String didTemplate) {
        this.repository = repository;
        this.onboardingApi = onboardingApi;
        this.tenantManager = tenantManager;
        this.didTemplate = didTemplate;
    }

    /**
     * Creates the membership, submits its registration and — once the registration is confirmed —
     * deploys the participant profile. The DID is resolved here — caller-supplied or
     * template-derived, the SAME rule the Onboarding API applies — and passed explicitly with the
     * registration, so the identity the holder is registered under and the identity the profile
     * is deployed as cannot drift.
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
            repository.save(current(externalId).failed("Registration submission failed: " + e.getMessage()));
            throw e;
        }
        // Reload: the status callback has recorded the registration's outcome on this record
        // while submitRegistration was on the wire.
        var current = current(externalId).withOnboardingProcessId(processId);
        var next = switch (current.state()) {
            case CONFIRMED -> provision(current, data);
            case REJECTED -> current;
            case SUBMITTED -> {
                // No callback arrived: the registration did not complete within the submitting
                // call (or the callback never reached this app). Nothing provisions this record
                // later — surfaced as REGISTERING so it is distinguishable from a confirmed one.
                log.warn("Membership '{}': no CONFIRMED callback within the submission — EDC resources are NOT provisioned",
                        externalId);
                yield current.withState(MembershipState.REGISTERING);
            }
            default -> current;
        };
        repository.save(next);
        return next;
    }

    /**
     * Records an Onboarding API status callback on the membership: CONFIRMED marks the
     * registration confirmed (provisioning itself is driven by {@link #onboard}, which picks the
     * marker up after the submission returns), REJECTED terminally rejects it.
     */
    public Membership onRegistrationStatus(String externalId, String status, String message) {
        var membership = current(externalId);
        if (membership.isTerminal()) {
            log.info("Membership '{}' is already {} — ignoring status update '{}'", externalId, membership.state(), status);
            return membership;
        }
        return switch (status == null ? "" : status.toUpperCase()) {
            case "CONFIRMED" -> {
                var confirmed = membership.withState(MembershipState.CONFIRMED);
                repository.save(confirmed);
                yield confirmed;
            }
            case "REJECTED" -> {
                log.warn("Membership '{}' was rejected by the Onboarding API: {}", externalId, message);
                var rejected = membership.rejected(message);
                repository.save(rejected);
                yield rejected;
            }
            default -> {
                log.debug("Membership '{}' received status '{}' — nothing to do", externalId, status);
                yield membership;
            }
        };
    }

    /**
     * The membership by its external id. When a participant profile has been deployed for it, its
     * current state is read from the Tenant Manager (resolved via the stored profile id) — that
     * is where the participant context id appears and deployment errors surface.
     */
    public Membership get(String externalId) {
        var membership = current(externalId);
        if (membership.participantProfileId() == null || membership.isTerminal()) {
            return membership;
        }
        var refreshed = applyProfile(membership, tenantManager.refresh(membership));
        repository.save(refreshed);
        return refreshed;
    }

    /**
     * Deploys the tenant + participant profile and stores the returned ids on the record. A
     * failure is recorded as a terminal FAILED — the registration side is done at this point, so
     * there is nothing to roll back to.
     */
    private Membership provision(Membership membership, MemberData payload) {
        var activeAgreements = payload.agreements().stream()
                .filter(MemberData.AgreementConsent::hasActiveConsent)
                .map(MemberData.AgreementConsent::agreementId)
                .toList();
        log.info("Membership '{}' confirmed — provisioning EDC resources for did={}", membership.externalId(), membership.did());
        try {
            var profile = tenantManager.deployParticipant(membership, activeAgreements);
            return applyProfile(membership.provisioning(profile.tenantId(), profile.participantProfileId()), profile);
        } catch (RuntimeException e) {
            log.error("Membership '{}' failed to provision", membership.externalId(), e);
            return membership.failed("Provisioning failed: " + e.getMessage());
        }
    }

    private Membership applyProfile(Membership membership, TenantManager.ProvisionedProfile profile) {
        if (profile.error()) {
            return membership.failed("Participant profile '%s' reported a deployment error"
                    .formatted(profile.participantProfileId()));
        }
        if (profile.participantContextId() == null) {
            return membership;
        }
        log.info("Membership '{}' is provisioned (participant context '{}')",
                membership.externalId(), profile.participantContextId());
        return membership.withParticipantContextId(profile.participantContextId()).provisioned();
    }

    private Membership current(String externalId) {
        return repository.findByExternalId(externalId)
                .orElseThrow(() -> new NoSuchElementException("No membership with external id " + externalId));
    }
}
