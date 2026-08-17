package com.metaform.cxve.adapter.out.validation;

import com.metaform.cxve.adapter.out.persistence.InMemoryOnboardingRepository;
import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.UniqueIdentifierId;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RegistrationValidationServiceImplTest {

    private final InMemoryOnboardingRepository repository = new InMemoryOnboardingRepository();
    private final RegistrationValidationServiceImpl validation = new RegistrationValidationServiceImpl(repository);

    private static PartnerRegistrationData registration(String externalId, String bpn, String did,
                                                        List<CompanyUniqueIdData> uniqueIds) {
        return new PartnerRegistrationData(
                "Acme Corp", "Berlin", "Musterstrasse", "DE", bpn, "Acme", "BE",
                null, null, null, uniqueIds, externalId, List.of(),
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), did, null, null, null);
    }

    /**
     * Seeds an onboarding in the given state, as the orchestrator would leave it behind: the
     * process carries the payload's BPN and DID from submission, since it is authoritative for
     * both. (Production resolves a template DID when none is submitted; the raw value here keeps
     * the absent-identity cases testable.)
     */
    private void onboarding(OnboardingState state, PartnerRegistrationData data) {
        var process = OnboardingProcess
                .submitted("process-" + data.externalId(), data.externalId(), data.bpn(), data.did())
                .withState(state);
        repository.create(process, data);
    }

    @Test
    void newPartner_passes() {
        onboarding(OnboardingState.COMPLETED, registration("ext-1", "BPNL000000000001", "did:web:acme", List.of()));

        var result = validation.validate(registration("ext-2", "BPNL000000000002", "did:web:other", List.of()));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void duplicateBpn_ofOnboardedPartner_isRejected() {
        onboarding(OnboardingState.COMPLETED, registration("ext-1", "BPNL000000000001", null, List.of()));

        var result = validation.validate(registration("ext-2", "BPNL000000000001", null, List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("BPN", "already registered"));
    }

    @Test
    void duplicateBpn_ofInFlightRegistration_isRejected() {
        onboarding(OnboardingState.VALIDATED, registration("ext-1", "BPNL000000000001", null, List.of()));

        var result = validation.validate(registration("ext-2", "BPNL000000000001", null, List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("BPN", "already in flight"));
    }

    @Test
    void duplicateDid_isRejected() {
        onboarding(OnboardingState.COMPLETED, registration("ext-1", null, "did:web:acme", List.of()));

        var result = validation.validate(registration("ext-2", null, "did:web:acme", List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("DID", "already registered"));
    }

    @Test
    void duplicateUniqueId_isRejected() {
        var vatId = new CompanyUniqueIdData(UniqueIdentifierId.VAT_ID, "DE123456789");
        onboarding(OnboardingState.COMPLETED, registration("ext-1", null, null, List.of(vatId)));

        var result = validation.validate(registration("ext-2", null, null, List.of(vatId)));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("unique id", "already registered"));
    }

    @Test
    void submittedButNotYetValidated_doesNotBlock() {
        // Also what keeps a registration from matching itself while it is being validated.
        onboarding(OnboardingState.SUBMITTED, registration("ext-1", "BPNL000000000001", null, List.of()));

        var result = validation.validate(registration("ext-2", "BPNL000000000001", null, List.of()));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void failedRegistration_doesNotBlock() {
        onboarding(OnboardingState.FAILED, registration("ext-1", "BPNL000000000001", null, List.of()));

        var result = validation.validate(registration("ext-2", "BPNL000000000001", null, List.of()));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void absentBpnAndDid_doNotMatchStoredNulls() {
        onboarding(OnboardingState.COMPLETED, registration("ext-1", null, null, List.of()));

        var result = validation.validate(registration("ext-2", null, null, List.of()));

        assertThat(result.valid()).isTrue();
    }
}
