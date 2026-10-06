package com.metaform.cxve.hub.adapter.out.persistence;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-memory store must mirror the JPA store's optimistic-lock compare-and-swap exactly —
 * the service tests run against THIS implementation, so its concurrency semantics are what they
 * actually exercise.
 */
class InMemoryMembershipRepositoryTest {

    private final InMemoryMembershipRepository repository = new InMemoryMembershipRepository();

    private static MemberData payload() {
        return new MemberData("catena-x", "Acme Corp", "Acme", "BPNL0000000000XY", null, Map.of("city", "Berlin"));
    }

    @Test
    void save_bumpsTheVersionAndRejectsStaleSnapshots() {
        repository.create(Membership.submitted("ext-1", "catena-x", "Acme Corp", "did:web:acme", "BPNL0000000000XY"), payload());
        var snapshot = repository.findByExternalId("ext-1").orElseThrow();
        assertThat(snapshot.version()).isEqualTo(0L);

        repository.save(snapshot.withState(MembershipState.CONFIRMED));
        assertThat(repository.findByExternalId("ext-1").orElseThrow().version()).isEqualTo(1L);

        assertThatThrownBy(() -> repository.save(snapshot.withState(MembershipState.REJECTED)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findByExternalId("ext-1").orElseThrow().state())
                .isEqualTo(MembershipState.CONFIRMED);
    }
}
