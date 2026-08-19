package com.metaform.cxve.adapter.out.persistence.jpa;

import com.metaform.cxve.domain.model.AgreementConsentData;
import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.ConsentStatusId;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.UniqueIdentifierId;
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
        return new PartnerRegistrationData(
                "Acme Corp", "Berlin", "Musterstrasse", "DE", bpn, "Acme", "BE",
                null, "12", "10115",
                List.of(new CompanyUniqueIdData(UniqueIdentifierId.VAT_ID, "DE123456789")),
                "ext-123", List.of(),
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), did,
                List.of(new AgreementConsentData("agreement-1", ConsentStatusId.ACTIVE)),
                null, null);
    }

    @Test
    void createAndFindById_roundTripsEveryProcessField() {
        var process = new OnboardingProcess("proc-1", "ext-123", OnboardingState.WALLET_PROVISIONED,
                "BPNL0000000000XY", "profile-1", "did:web:acme", "why not", "holder-pid-1", "tenant-1", "pctx-1",
                "client-1");

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
    void findByHolderId_findsTheLinkedProcess() {
        repository.create(OnboardingProcess.submitted("proc-1", "ext-1", "BPNL1", null), registration("BPNL1", null));
        repository.save(repository.findById("proc-1").orElseThrow().withHolderId("did:web:acme"));

        assertThat(repository.findByHolderId("did:web:acme").orElseThrow().id()).isEqualTo("proc-1");
        assertThat(repository.findByHolderId("did:web:other")).isEmpty();
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
    void findByHolderId_prefersTheRunningOnboardingOverARejectedDuplicate() {
        // The holder DID is seeded at submission, so a rejected duplicate registration carries the
        // same one as the onboarding it duplicated. Issuance events must keep resolving to the one
        // that is still running, whichever row the database returns first.
        var running = OnboardingProcess.submitted("proc-running", "ext-1", "BPNL1", "did:web:acme")
                .withState(OnboardingState.VALIDATED);
        repository.create(running, registration("BPNL1", "did:web:acme"));
        var duplicate = OnboardingProcess.submitted("proc-dup", "ext-2", "BPNL1", "did:web:acme")
                .rejected("BPN already in flight");
        repository.create(duplicate, registration("BPNL1", "did:web:acme"));

        assertThat(repository.findByHolderId("did:web:acme").orElseThrow().id()).isEqualTo("proc-running");

        // Once no onboarding is running, a terminal match still resolves (redelivered events).
        repository.save(running.rejected("also over"));
        assertThat(repository.findByHolderId("did:web:acme")).isPresent();
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
