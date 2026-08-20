package com.metaform.cxve.hub.domain.port;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import java.util.Optional;

/**
 * Persistence boundary for membership state, keyed by the {@code externalId} this app mints —
 * the id the Onboarding API's status callbacks are correlated on. The original request payload is
 * stored alongside the record because provisioning replays parts of it (the agreements) after the
 * asynchronous CONFIRMED callback.
 */
public interface MembershipRepository {

    /** Persists a newly submitted membership together with the request it was created from. */
    void create(Membership membership, MemberData payload);

    /** Upserts the membership after a state transition. */
    void save(Membership membership);

    Optional<Membership> findByExternalId(String externalId);

    /** The request payload the membership was created from. */
    Optional<MemberData> findPayload(String externalId);
}
