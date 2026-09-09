package com.metaform.cxve.adapter.out.persistence.jpa;

import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.ConsentData;
import com.metaform.cxve.domain.model.ConsentKind;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.UniqueIdentifierId;
import com.metaform.cxve.domain.model.UserDetailData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link JpaOnboardingRepository} through the {@link
 * com.metaform.cxve.domain.port.OnboardingRepository} port against a real EntityManager (embedded
 * H2 — the schema is vanilla JPA DDL, no Postgres-only constructs). The assertions mirror the
 * semantics the in-memory implementation established, since the two must be interchangeable.
 */
// Deliberately NOT the "test" profile: that profile excludes the JPA machinery this slice is
// exercising. The slice replaces the configured datasource with the embedded database anyway.
@DataJpaTest
class JpaOnboardingRepositoryTest {

    @Autowired
    private SpringDataOnboardingProcessRepository springData;

    private JpaOnboardingRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JpaOnboardingRepository(springData);
    }

    private static PartnerRegistrationData registration(String bpn, String did) {
        // Distinct value per component on purpose: the record-equality round-trip assertions
        // would miss silently swapped fields if values repeated.
        return new PartnerRegistrationData(
                "ext-123", "Acme Corp", "Berlin", "Musterstrasse", "DE", "BE",
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT),
                List.of(new CompanyUniqueIdData(UniqueIdentifierId.VAT_ID, "DE123456789")),
                List.of(new UserDetailData("idp-1", "prov-1", "jdoe", "John", "Doe", "john.doe@acme.example")),
                bpn, "Acme", "12", "Backyard", "10115", did, Boolean.TRUE,
                List.of(new ConsentData(ConsentKind.CX_OPERATING_MODEL, List.of("file-1"))));
    }

    @Test
    void createAndFindById_roundTripsEveryProcessField() {
        var process = new OnboardingProcess("proc-1", "ext-123", OnboardingState.WALLET_PROVISIONED,
                "BPNL0000000000XY", "did:web:acme", "why not", "client-1");

        repository.create(process, registration("BPNL0000000000XY", null));

        assertThat(repository.findById("proc-1")).contains(process);
        assertThat(repository.findById("no-such")).isEmpty();
    }

    @Test
    void findPayload_roundTripsTheRegistrationData() {
        var payload = registration("BPNL0000000000XY", "did:web:acme.example.com");
        repository.create(OnboardingProcess.submitted("proc-1", "ext-123", payload.bpn(), payload.did()), payload);

        // Record equality covers every component, nested records and enums included.
        assertThat(repository.findPayload("proc-1")).contains(payload);
    }

    @Test
    void save_transitionsTheProcessWithoutLosingThePayload() {
        var payload = registration("BPNL0000000000XY", null);
        var process = OnboardingProcess.submitted("proc-1", "ext-123", "BPNL0000000000XY", null);
        repository.create(process, payload);

        var advanced = process.withState(OnboardingState.VALIDATED).withBpn("BPNL0000000000XY");
        repository.save(advanced);

        assertThat(repository.findById("proc-1").orElseThrow().state()).isEqualTo(OnboardingState.BPN_ASSIGNED);
        assertThat(repository.findPayload("proc-1")).contains(payload);
    }

    @Test
    void activeQueries_excludeSubmittedAndTerminalFailures() {
        var process = OnboardingProcess.submitted("proc-1", "ext-1", "BPNL0000000000XY", null);
        repository.create(process, registration("BPNL0000000000XY", null));

        // SUBMITTED: a registration must not match itself while it is being validated.
        assertThat(repository.findActiveByBpn("BPNL0000000000XY")).isEmpty();

        repository.save(process.withState(OnboardingState.VALIDATED));
        assertThat(repository.findActiveByBpn("BPNL0000000000XY")).isPresent();

        // REJECTED and FAILED free the identifiers for re-registration.
        repository.save(process.rejected("nope"));
        assertThat(repository.findActiveByBpn("BPNL0000000000XY")).isEmpty();
        repository.save(process.failed("boom"));
        assertThat(repository.findActiveByBpn("BPNL0000000000XY")).isEmpty();
    }

    @Test
    void findActiveByBpn_followsTheAuthoritativeProcessBpn() {
        // The process BPN is seeded from the payload at submission and is authoritative from then
        // on: matchable as soon as the state allows, and if the BPN step resolves a different one,
        // the seeded value stops matching — the payload is never consulted.
        var process = OnboardingProcess.submitted("proc-1", "ext-1", "BPNL_SUBMITTED", null);
        repository.create(process, registration("BPNL_SUBMITTED", null));
        repository.save(process.withBpn("BPNL_RESOLVED"));

        var match = repository.findActiveByBpn("BPNL_RESOLVED");
        assertThat(match).isPresent();
        assertThat(match.orElseThrow().data().bpn()).isEqualTo("BPNL_RESOLVED");
        assertThat(repository.findActiveByBpn("BPNL_SUBMITTED")).isEmpty();
    }

    @Test
    void findActiveByDid_followsTheAuthoritativeHolderDid() {
        // Same authority rule for the DID: seeded (resolver-derived) at submission, overwritten
        // with the provisioned participant's identifier — in production the two are identical by
        // construction, but the store must follow the process value either way.
        var process = OnboardingProcess.submitted("proc-1", "ext-1", "BPNL1", "did:web:seeded");
        repository.create(process, registration("BPNL1", "did:web:seeded"));
        repository.save(process.withState(OnboardingState.VALIDATED));

        assertThat(repository.findActiveByDid("did:web:seeded")).isPresent();

        repository.save(process.withState(OnboardingState.VALIDATED).withHolderId("did:web:provisioned"));
        assertThat(repository.findActiveByDid("did:web:provisioned")).isPresent();
        assertThat(repository.findActiveByDid("did:web:seeded")).isEmpty();
    }

    @Test
    void findActiveByUniqueId_matchesOnTypeAndValue() {
        var process = OnboardingProcess.submitted("proc-1", "ext-1", "BPNL1", null);
        repository.create(process, registration("BPNL1", null));
        repository.save(process.withState(OnboardingState.VALIDATED));

        assertThat(repository.findActiveByUniqueId(
                new CompanyUniqueIdData(UniqueIdentifierId.VAT_ID, "DE123456789"))).isPresent();
        // Same value under a different type is a different identifier.
        assertThat(repository.findActiveByUniqueId(
                new CompanyUniqueIdData(UniqueIdentifierId.LEI_CODE, "DE123456789"))).isEmpty();
        assertThat(repository.findActiveByUniqueId(
                new CompanyUniqueIdData(UniqueIdentifierId.VAT_ID, "DE000000000"))).isEmpty();
    }

    @Test
    void findPayload_toleratesLegacyPayloadJson() {
        // Rows written under earlier spec revisions carry fields the current record no longer
        // has (agreements, fileIds) and miss fields it gained. @JsonIgnoreProperties on
        // PartnerRegistrationData is load-bearing here: the plain ObjectMapper the repository
        // uses would otherwise throw on every pre-existing row — including inside the
        // active-registration queries that gate NEW registrations.
        var entity = new OnboardingProcessEntity();
        entity.setId("legacy-1");
        entity.setExternalId("ext-legacy");
        entity.setState(OnboardingState.COMPLETED);
        entity.setPayload("""
                {
                  "name": "Legacy Corp",
                  "externalId": "ext-legacy",
                  "shortName": "Legacy",
                  "companyRoles": [ "ACTIVE_PARTICIPANT" ],
                  "agreements": [ { "agreementId": "Catena-X", "consentStatus": "ACTIVE" } ],
                  "fileIds": [ "file-1" ]
                }
                """);
        springData.save(entity);

        var payload = repository.findPayload("legacy-1");

        assertThat(payload).isPresent();
        assertThat(payload.get().name()).isEqualTo("Legacy Corp");
        assertThat(payload.get().city()).isNull();
    }

    @Test
    void existsByClientIdAndExternalId_isScopedToTheClient() {
        var process = OnboardingProcess.submitted("proc-1", "ext-1", null, null, "client-1");
        repository.create(process, registration(null, null));

        assertThat(repository.existsByClientIdAndExternalId("client-1", "ext-1")).isTrue();
        // The same externalId under another OSP is no conflict (uniqueness is per OSP)...
        assertThat(repository.existsByClientIdAndExternalId("client-2", "ext-1")).isFalse();
        // ...nor is another externalId of the same OSP.
        assertThat(repository.existsByClientIdAndExternalId("client-1", "ext-2")).isFalse();
    }

    @Test
    void existsByClientIdAndExternalId_countsTerminalAttempts() {
        // The §2.2.2 conflict check is deliberately any-state: a declined registration keeps its
        // externalId — OSPs mint a fresh one per registration.
        var process = OnboardingProcess.submitted("proc-1", "ext-1", null, null, "client-1");
        repository.create(process, registration(null, null));
        repository.save(process.rejected("nope"));

        assertThat(repository.existsByClientIdAndExternalId("client-1", "ext-1")).isTrue();
    }

    @Test
    void inactiveStates_matchTheDomainPredicate() {
        // The SQL queries take the complement of isActiveRegistration() via INACTIVE_STATES; if a
        // new OnboardingState changes the domain predicate, this fails until the constant follows.
        for (var state : OnboardingState.values()) {
            var process = OnboardingProcess.submitted("p", "e", null, null).withState(state);
            assertThat(JpaOnboardingRepository.INACTIVE_STATES.contains(state))
                    .as("state %s", state)
                    .isEqualTo(!process.isActiveRegistration());
        }
    }
}
