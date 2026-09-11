package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.Membership;
import com.metaform.cxve.verification.domain.model.VerificationParticipant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.metaform.cxve.verification.adapter.out.management.ManagementApiClient.DATA_USAGE_DEFINITION_CONSTRAINT;
import static com.metaform.cxve.verification.adapter.out.management.ManagementApiClient.FRAMEWORK_AGREEMENT_CONSTRAINT;
import static com.metaform.cxve.verification.adapter.out.management.ManagementApiClient.MEMBERSHIP_CONSTRAINT;
import static com.metaform.cxve.verification.adapter.out.management.ManagementApiClient.USAGE_PURPOSE_CONSTRAINT;

/**
 * Owns the PERMANENT verification participant — the difference to the e2e suite, which onboards
 * its consumer fresh every run. {@link #ensure()} is idempotent and survives restarts: the
 * participant is rediscovered in the hub by its fixed BPN (durable in the hub's database),
 * onboarded only when truly absent, and its permanent CCM inbox offer is re-seeded through
 * idempotent creates on every ensure — so a half-finished earlier attempt heals rather than
 * fails.
 */
@Service
public class VerificationParticipantService {

    private static final Logger log = LoggerFactory.getLogger(VerificationParticipantService.class);

    private final MembershipHubClient hub;
    private final ManagementApiClient management;
    private final CertoClient certo;
    private final VerificationProperties properties;
    private final AtomicReference<VerificationParticipant> cached = new AtomicReference<>();
    private volatile boolean offerSeeded;

    public VerificationParticipantService(MembershipHubClient hub,
                                          ManagementApiClient management,
                                          CertoClient certo,
                                          VerificationProperties properties) {
        this.hub = hub;
        this.management = management;
        this.certo = certo;
        this.properties = properties;
    }

    /**
     * The verification participant, created on first use: rediscover by BPN, onboard if absent,
     * await PROVISIONED, await the certo tenant, seed the permanent inbox offer. Synchronized —
     * concurrent runs must not double-onboard; the first blocks (up to the onboarding timeout,
     * possibly minutes), the rest reuse its result.
     */
    public synchronized VerificationParticipant ensure() {
        var current = cached.get();
        if (current != null && offerSeeded) {
            return current;
        }
        var identity = properties.participant();
        var membership = current != null ? hub.get(current.externalId()) : findLiveMembership(identity.bpn());
        if (membership == null) {
            log.info("no verification participant with BPN {} — onboarding \"{}\"", identity.bpn(), identity.name());
            membership = hub.onboard(identity.name(), identity.shortName(), identity.bpn(), identity.vatId());
        } else {
            log.info("verification participant found: externalId={}, state={}", membership.externalId(), membership.state());
        }
        if (!membership.isProvisioned()) {
            membership = hub.awaitProvisioned(membership.externalId());
        }
        var participant = VerificationParticipant.from(membership);
        certo.awaitParticipantContext(participant.participantContextId());
        seedInboxOffer(participant.participantContextId());
        cached.set(participant);
        return participant;
    }

    /**
     * The current state WITHOUT side effects — no onboarding, no waiting; safe for the
     * dashboard's poll (deliberately not synchronized, so it never blocks behind a running
     * ensure). {@code offerSeeded} is process-local knowledge: after a restart it reads false
     * until the first ensure re-runs the (cheap, idempotent) seeding.
     */
    public Status status() {
        var current = cached.get();
        var membership = current != null
                ? hub.get(current.externalId())
                : findLiveMembership(properties.participant().bpn());
        return new Status(membership != null, membership, offerSeeded);
    }

    private Membership findLiveMembership(String bpn) {
        // dead attempts (REJECTED/FAILED/REGISTERING) do not retire the BPN — skip them
        return hub.findByBpn(bpn).stream()
                .filter(membership -> !membership.isDeadEnd())
                .findFirst()
                .orElse(null);
    }

    /**
     * The permanent inbox offer on the verification participant: the asset the
     * participant-under-test consumes to obtain the push flow. Fixed (non-run-scoped) ids; the
     * offer is counterparty-agnostic, which is what makes one permanent asset serve every run.
     */
    private void seedInboxOffer(String pcid) {
        var accessPolicyId = "vui-ccm-access-policy";
        var contractPolicyId = "vui-ccm-contract-policy";
        management.createAssetIdempotent(pcid, properties.inboxAssetId(), properties.certoAssetBaseUrl());
        management.createPolicyIdempotent(pcid, accessPolicyId, "access", List.of(MEMBERSHIP_CONSTRAINT));
        management.createPolicyIdempotent(pcid, contractPolicyId, "use",
                List.of(FRAMEWORK_AGREEMENT_CONSTRAINT, USAGE_PURPOSE_CONSTRAINT, DATA_USAGE_DEFINITION_CONSTRAINT));
        management.createContractDefinitionIdempotent(pcid, "vui-ccm-cd", accessPolicyId, contractPolicyId);
        offerSeeded = true;
        log.info("verification participant inbox offer ready (asset '{}')", properties.inboxAssetId());
    }

    /** The dashboard view: whether the participant exists, its record, and offer seeding. */
    public record Status(boolean exists, Membership membership, boolean offerSeeded) {
    }
}
