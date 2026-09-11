package com.metaform.cxve.hub.adapter.out.persistence;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.port.MembershipRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link MembershipRepository}, active only under the {@code test} profile. State is
 * lost on restart; everywhere else the durable {@code JpaMembershipRepository} is the default
 * (complementary profile expressions, so exactly one of the two exists in any context) — and
 * the two must stay interchangeable, so {@link #save} mirrors the JPA store's version
 * compare-and-swap exactly.
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
    public synchronized void save(Membership membership) {
        var stored = memberships.get(membership.externalId());
        if (stored != null && !Objects.equals(stored.version(), membership.version())) {
            throw new OptimisticLockingFailureException(
                    "Membership %s changed concurrently (stored version %s, snapshot version %s) — reload and re-apply"
                            .formatted(membership.externalId(), stored.version(), membership.version()));
        }
        var nextVersion = stored == null || stored.version() == null ? 0L : stored.version() + 1;
        memberships.put(membership.externalId(), membership.withVersion(nextVersion));
    }

    @Override
    public Optional<Membership> findByExternalId(String externalId) {
        return Optional.ofNullable(memberships.get(externalId));
    }

    @Override
    public List<Membership> findByBpn(String bpn) {
        return memberships.values().stream()
                .filter(membership -> Objects.equals(membership.bpn(), bpn))
                .toList();
    }

    @Override
    public Optional<MemberData> findPayload(String externalId) {
        return Optional.ofNullable(payloads.get(externalId));
    }
}
