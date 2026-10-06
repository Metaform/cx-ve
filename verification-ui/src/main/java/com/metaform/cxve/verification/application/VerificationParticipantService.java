package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.CcmApi;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.Membership;
import com.metaform.cxve.verification.domain.model.VerificationParticipant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the PERMANENT verification participants — one per dataspace, since a participant is a
 * member of exactly the dataspace it was onboarded into. The difference to the e2e suite, which
 * onboards its consumer fresh every run. {@link #ensure} is idempotent and survives restarts: the
 * participant is rediscovered in the hub by its dataspace and fixed member id (durable in the hub's
 * database), onboarded only when truly absent, and its permanent CCM inbox offer is re-seeded
 * through idempotent creates on every ensure — so a half-finished earlier attempt heals rather
 * than fails.
 *
 * <p>Each dataspace's participant needs its own short name (it forms the hosted DID, and the hub
 * deploys a DID only once across dataspaces) — the dataspace profiles set them.
 */
@Service
public class VerificationParticipantService {

    private static final Logger log = LoggerFactory.getLogger(VerificationParticipantService.class);

    private final MembershipHubClient hub;
    private final ManagementApiClient management;
    private final CertoClient certo;
    private final VerificationProperties properties;
    private final Map<String, VerificationParticipant> cached = new ConcurrentHashMap<>();
    private final Set<String> offerSeeded = ConcurrentHashMap.newKeySet();

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
     * The dataspace's verification participant, created on first use: rediscover by member id,
     * onboard if absent, await PROVISIONED, await the certo tenant, seed the permanent inbox offer.
     * Synchronized — concurrent runs must not double-onboard; the first blocks (up to the
     * onboarding timeout, possibly minutes), the rest reuse its result.
     */
    public synchronized VerificationParticipant ensure(String dataspace) {
        var current = cached.get(dataspace);
        if (current != null && offerSeeded.contains(dataspace)) {
            return current;
        }
        var identity = properties.dataspace(dataspace).verificationParticipant();
        var membership = current != null ? hub.get(current.externalId()) : findLiveMembership(dataspace, identity.memberId());
        if (membership == null) {
            log.info("no {} verification participant with member id {} — onboarding \"{}\"",
                    dataspace, identity.memberId(), identity.name());
            membership = hub.onboard(dataspace, identity.name(), identity.shortName(), identity.memberId(),
                    identity.uniqueId(), null);
        } else {
            log.info("{} verification participant found: externalId={}, state={}",
                    dataspace, membership.externalId(), membership.state());
        }
        if (!membership.hasParticipantResources()) {
            membership = hub.awaitProvisioned(membership.externalId());
        }
        if (!membership.isCredentialsOffered()) {
            // The participant is the consumer of every run's certificate exchange, so it needs its
            // own credentials before it can negotiate anything. They are offered as part of its
            // registration, which the hub submits once the deployment is done; delivery to its
            // wallet follows on its own.
            membership = hub.awaitCredentialsOffered(membership.externalId());
        }
        var participant = VerificationParticipant.from(membership);
        certo.awaitParticipantContext(participant.participantContextId());
        seedInboxOffer(dataspace, participant.participantContextId());
        cached.put(dataspace, participant);
        return participant;
    }

    /**
     * The dataspace's current state WITHOUT side effects — no onboarding, no waiting; safe for the
     * dashboard's poll (deliberately not synchronized, so it never blocks behind a running
     * ensure). Offer seeding is process-local knowledge: after a restart it reads false until the
     * first ensure re-runs the (cheap, idempotent) seeding.
     */
    public Status status(String dataspace) {
        var current = cached.get(dataspace);
        var membership = current != null
                ? hub.get(current.externalId())
                : findLiveMembership(dataspace, properties.dataspace(dataspace).verificationParticipant().memberId());
        return new Status(dataspace, membership != null, membership, offerSeeded.contains(dataspace));
    }

    private Membership findLiveMembership(String dataspace, String memberId) {
        // dead attempts (REJECTED/FAILED/REGISTERING) do not retire the member id — skip them
        return hub.findByMemberId(dataspace, memberId).stream()
                .filter(membership -> !membership.isDeadEnd())
                .findFirst()
                .orElse(null);
    }

    /**
     * The permanent inbox offer on the verification participant: the asset the
     * participant-under-test consumes to obtain the push flow, under the dataspace's policies.
     * Fixed (non-run-scoped) ids; the offer is counterparty-agnostic, which is what makes one
     * permanent asset serve every run.
     *
     * <p>Fixed per DATASPACE, though: the control plane's ids are unique across all participant
     * contexts, not just within one, so two dataspaces' participants cannot both own a
     * {@code vui-ccm-cd}. The inbox asset id is the dataspace profile's own (and must differ
     * between profiles, which {@link VerificationProperties} checks at startup).
     */
    private void seedInboxOffer(String dataspace, String pcid) {
        var profile = properties.dataspace(dataspace);
        var ccm = profile.useCase("ccm").ccm();
        var accessPolicyId = "vui-ccm-access-policy-" + dataspace;
        var contractPolicyId = "vui-ccm-contract-policy-" + dataspace;
        // Declared as the CCM consumer API: a participant pushing to this inbox finds the offer by
        // that, not by the id, which is this environment's own choice.
        management.upsertAsset(pcid, ccm.inboxAssetId(), CcmApi.consumer(ccm.api()));
        management.createPolicyIdempotent(pcid, accessPolicyId, "access", profile.policyContext(),
                profile.accessConstraints());
        management.createPolicyIdempotent(pcid, contractPolicyId, "use", profile.policyContext(),
                profile.contractConstraints());
        management.createContractDefinitionIdempotent(pcid, "vui-ccm-cd-" + dataspace, accessPolicyId, contractPolicyId);
        offerSeeded.add(dataspace);
        log.info("{} verification participant inbox offer ready (asset '{}')", dataspace, ccm.inboxAssetId());
    }

    /** The dashboard view: whether the dataspace's participant exists, its record, and offer seeding. */
    public record Status(String dataspace, boolean exists, Membership membership, boolean offerSeeded) {
    }
}
