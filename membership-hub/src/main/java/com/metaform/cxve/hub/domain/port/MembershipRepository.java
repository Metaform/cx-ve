package com.metaform.cxve.hub.domain.port;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for membership state, keyed by the {@code externalId} this app mints —
 * the id the onboarding APIs' status callbacks are correlated on. The original request payload is
 * stored alongside the record because the background worker replays it: the deployment and the
 * registration that follows are both built from it.
 */
public interface MembershipRepository {

    /** Persists a newly submitted membership together with the request it was created from. */
    void create(Membership membership, MemberData payload);

    /** Upserts the membership after a state transition. */
    void save(Membership membership);

    Optional<Membership> findByExternalId(String externalId);

    /**
     * All memberships of the dataspace carrying the given member id. More than one can exist — a
     * REJECTED or FAILED attempt does not retire its member id — so callers filter by state.
     */
    List<Membership> findByMemberId(String dataspace, String memberId);

    /**
     * All memberships carrying the given DID, same multiplicity rule as {@link #findByMemberId}, across dataspaces. This
     * is how a caller holding only an externally hosted member's identity finds the membership it
     * already has: the Onboarding API refuses to register a DID that is already registered, so
     * onboarding such a member a second time would be declined rather than repeated.
     */
    List<Membership> findByDid(String did);

    /** The request payload the membership was created from. */
    Optional<MemberData> findPayload(String externalId);
}
