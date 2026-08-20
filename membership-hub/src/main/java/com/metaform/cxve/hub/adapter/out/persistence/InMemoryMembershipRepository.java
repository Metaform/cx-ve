package com.metaform.cxve.hub.adapter.out.persistence;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.port.MembershipRepository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link MembershipRepository}, active only under the {@code test} profile. State is
 * lost on restart; everywhere else the durable {@code JpaMembershipRepository} is the default
 * (complementary profile expressions, so exactly one of the two exists in any context).
 */
@Repository
@Profile("test")
public class InMemoryMembershipRepository implements MembershipRepository {

    private final ConcurrentHashMap<String, Membership> memberships = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemberData> payloads = new ConcurrentHashMap<>();

    @Override
    public void create(Membership membership, MemberData payload) {
        payloads.put(membership.externalId(), payload);
        save(membership);
    }

    @Override
    public void save(Membership membership) {
        memberships.put(membership.externalId(), membership);
    }

    @Override
    public Optional<Membership> findByExternalId(String externalId) {
        return Optional.ofNullable(memberships.get(externalId));
    }

    @Override
    public Optional<MemberData> findPayload(String externalId) {
        return Optional.ofNullable(payloads.get(externalId));
    }
}
