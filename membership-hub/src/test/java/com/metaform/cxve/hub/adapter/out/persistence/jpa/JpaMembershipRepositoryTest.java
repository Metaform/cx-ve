package com.metaform.cxve.hub.adapter.out.persistence.jpa;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link JpaMembershipRepository} through the port against a real EntityManager
 * (embedded H2 — the schema is vanilla JPA DDL, no Postgres-only constructs). The assertions
 * mirror the in-memory implementation's semantics, since the two must be interchangeable.
 */
// Deliberately NOT the "test" profile: that profile excludes the JPA machinery this slice is
// exercising. The slice replaces the configured datasource with the embedded database anyway.
@DataJpaTest
class JpaMembershipRepositoryTest {

    @Autowired
    private SpringDataMembershipRepository springData;

    private JpaMembershipRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JpaMembershipRepository(springData);
    }

    private static MemberData payload() {
        return new MemberData("Acme Corp", "Acme", "BPNL0000000000XY",
                "Berlin", "Musterstrasse", "DE", "BE", null,
                List.of(new MemberData.UniqueId("VAT_ID", "DE123456789")),
                List.of("ACTIVE_PARTICIPANT"),
                List.of(new MemberData.AgreementConsent("agreement-1", "ACTIVE")),
                List.of(new MemberData.UserDetail(null, "prov-1", "jdoe", "John", "Doe", "john.doe@acme.example")));
    }

    @Test
    void createAndFindByExternalId_roundTripsEveryField() {
        var membership = new Membership("ext-1", "Acme Corp", "did:web:acme", "BPNL0000000000XY",
                MembershipState.PROVISIONING, "process-1", "tenant-1", "profile-1", "pctx-1", "why not", null);

        repository.create(membership, payload());
        springData.flush();

        // the stored row carries the initial lock version; every other field round-trips as-is
        assertThat(repository.findByExternalId("ext-1")).contains(membership.withVersion(0L));
        assertThat(repository.findByExternalId("no-such")).isEmpty();
    }

    @Test
    void findPayload_roundTripsTheRequestData() {
        var data = payload();
        repository.create(Membership.submitted("ext-1", data.name(), "did:web:acme", data.bpn()), data);

        // Record equality covers every component, nested records included.
        assertThat(repository.findPayload("ext-1")).contains(data);
    }

    @Test
    void findByBpn_listsEveryAttemptUnderTheBpn() {
        var data = payload();
        repository.create(Membership.submitted("ext-1", "Acme Corp", "did:web:acme", "BPNL0000000000XY"), data);
        // a second attempt under the SAME BPN (e.g. after a rejected registration) must show up too
        repository.create(Membership.submitted("ext-2", "Acme Corp", "did:web:acme2", "BPNL0000000000XY"), data);
        repository.create(Membership.submitted("ext-3", "Other Corp", "did:web:other", "BPNLOTHER0000001"), data);

        assertThat(repository.findByBpn("BPNL0000000000XY"))
                .extracting(Membership::externalId)
                .containsExactlyInAnyOrder("ext-1", "ext-2");
        assertThat(repository.findByBpn("BPNLUNKNOWN00001")).isEmpty();
    }

    @Test
    void save_transitionsTheMembershipWithoutLosingThePayload() {
        var data = payload();
        repository.create(Membership.submitted("ext-1", data.name(), "did:web:acme", data.bpn()), data);
        springData.flush();

        // save wants the STORED snapshot (its version is the lock token), not the pre-create one
        var current = repository.findByExternalId("ext-1").orElseThrow();
        repository.save(current.withOnboardingProcessId("process-1").withState(MembershipState.CONFIRMED));

        var stored = repository.findByExternalId("ext-1").orElseThrow();
        assertThat(stored.state()).isEqualTo(MembershipState.CONFIRMED);
        assertThat(stored.onboardingProcessId()).isEqualTo("process-1");
        assertThat(repository.findPayload("ext-1")).contains(data);
    }

    @Test
    void save_rejectsAStaleSnapshot() {
        var data = payload();
        repository.create(Membership.submitted("ext-1", data.name(), "did:web:acme", data.bpn()), data);
        springData.flush();
        var snapshot = repository.findByExternalId("ext-1").orElseThrow();

        // another writer advances the row; the flush bumps the stored version
        repository.save(snapshot.withState(MembershipState.CONFIRMED));
        springData.flush();

        // the stale snapshot must be refused — its blind write would eat the CONFIRMED
        assertThatThrownBy(() -> repository.save(snapshot.withOnboardingProcessId("process-1")))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findByExternalId("ext-1").orElseThrow().state())
                .isEqualTo(MembershipState.CONFIRMED);

        // a fresh snapshot goes through
        var fresh = repository.findByExternalId("ext-1").orElseThrow();
        repository.save(fresh.withOnboardingProcessId("process-1"));
        assertThat(repository.findByExternalId("ext-1").orElseThrow().onboardingProcessId())
                .isEqualTo("process-1");
    }
}
