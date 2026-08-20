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
 * Sequences a membership's two legs and maintains the correlation record between them.
 *
 * <p>{@link #onboard} persists the record FIRST and only then submits the registration: the
 * Onboarding API fires its CONFIRMED callback synchronously when nothing gates its flow, so
 * {@link #onRegistrationStatus} may run (on the callback thread) before the submitting call
 * returns — it must find the record. For the same reason the post-submit save goes through
 * {@link Membership#registering()}, which never rolls back progress the callback has already made.
 *
 * <p>Provisioning happens only on the CONFIRMED callback — there is deliberately no polling of
 * the Onboarding API. The Tenant Manager side IS polled, but lazily: {@link #get} refreshes a
 * PROVISIONING membership on read instead of running a background loop.
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
     * Creates the membership and submits its registration to the Onboarding API. The DID is
     * resolved here — caller-supplied or template-derived, the SAME rule the Onboarding API
     * applies — and passed explicitly with the registration, so the identity the holder is
     * registered under and the identity the profile is later deployed as cannot drift.
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
        // Reload before transitioning: the CONFIRMED callback may have advanced the record while
        // submitRegistration was still on the wire. registering() only moves SUBMITTED forward,
        // so that progress is kept; the onboarding process id is recorded either way.
        var next = current(externalId).withOnboardingProcessId(processId).registering();
        repository.save(next);
        return next;
    }

    /**
     * Reacts to an Onboarding API status callback. CONFIRMED is the gate this app exists for: the
     * partner is registered and its credential holder exists, so the EDC resources may now be
     * provisioned.
     */
    public Membership onRegistrationStatus(String externalId, String status, String message) {
        var membership = current(externalId);
        if (membership.isTerminal()) {
            log.info("Membership '{}' is already {} — ignoring status update '{}'", externalId, membership.state(), status);
            return membership;
        }
        return switch (status == null ? "" : status.toUpperCase()) {
            case "CONFIRMED" -> provision(membership);
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
     * The membership by its external id — with a lazy provisioning refresh: a PROVISIONING record
     * is re-checked against the Tenant Manager on read, so the participant context id appears
     * without a background poller.
     */
    public Membership get(String externalId) {
        var membership = current(externalId);
        if (membership.state() != MembershipState.PROVISIONING) {
            return membership;
        }
        var refreshed = applyProfile(membership, tenantManager.refresh(membership));
        repository.save(refreshed);
        return refreshed;
    }

    private Membership provision(Membership membership) {
        var payload = repository.findPayload(membership.externalId())
                .orElseThrow(() -> new IllegalStateException(
                        "No stored payload for membership " + membership.externalId()));
        var activeAgreements = payload.agreements().stream()
                .filter(MemberData.AgreementConsent::hasActiveConsent)
                .map(MemberData.AgreementConsent::agreementId)
                .toList();
        log.info("Membership '{}' confirmed — provisioning EDC resources for did={}", membership.externalId(), membership.did());
        Membership next;
        try {
            var profile = tenantManager.deployParticipant(membership, activeAgreements);
            next = applyProfile(membership.provisioning(profile.tenantId(), profile.participantProfileId()), profile);
        } catch (RuntimeException e) {
            log.error("Membership '{}' failed to provision", membership.externalId(), e);
            next = membership.failed("Provisioning failed: " + e.getMessage());
        }
        repository.save(next);
        return next;
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
