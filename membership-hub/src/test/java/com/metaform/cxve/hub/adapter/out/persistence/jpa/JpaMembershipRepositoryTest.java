package com.metaform.cxve.hub.adapter.out.persistence.jpa;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

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
        return new MemberData("Acme Corp", "Acme", "BPNL0000000000XY", null,
                List.of(new MemberData.UniqueId("VAT_ID", "DE123456789")),
                List.of("ACTIVE_PARTICIPANT"),
                List.of(new MemberData.AgreementConsent("agreement-1", "ACTIVE")));
    }

    @Test
    void createAndFindByExternalId_roundTripsEveryField() {
        var membership = new Membership("ext-1", "Acme Corp", "did:web:acme", "BPNL0000000000XY",
                MembershipState.PROVISIONING, "process-1", "tenant-1", "profile-1", "pctx-1", "why not");

        repository.create(membership, payload());

        assertThat(repository.findByExternalId("ext-1")).contains(membership);
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
    void save_transitionsTheMembershipWithoutLosingThePayload() {
        var data = payload();
        var membership = Membership.submitted("ext-1", data.name(), "did:web:acme", data.bpn());
        repository.create(membership, data);

        repository.save(membership.withOnboardingProcessId("process-1").registering());

        var stored = repository.findByExternalId("ext-1").orElseThrow();
        assertThat(stored.state()).isEqualTo(MembershipState.REGISTERING);
        assertThat(stored.onboardingProcessId()).isEqualTo("process-1");
        assertThat(repository.findPayload("ext-1")).contains(data);
    }
}
