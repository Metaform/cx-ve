package com.metaform.cxve.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationStatusTest {

    @ParameterizedTest
    @EnumSource(OnboardingState.class)
    void everyProcessState_hasAWireStatus(OnboardingState state) {
        // The read endpoints map ANY state — the old mapping threw for intermediates, which was
        // fine when only terminal states hit the wire; a GET can see a process mid-flight.
        assertThat(RegistrationStatus.from(state)).isNotNull();
    }

    @Test
    void mapping_followsTheSpecStateMachines() {
        // In flight reads as SUBMITTED (the tenant state machine's entry state) ...
        assertThat(RegistrationStatus.from(OnboardingState.SUBMITTED)).isEqualTo(RegistrationStatus.SUBMITTED);
        assertThat(RegistrationStatus.from(OnboardingState.VALIDATED)).isEqualTo(RegistrationStatus.SUBMITTED);
        assertThat(RegistrationStatus.from(OnboardingState.BPN_ASSIGNED)).isEqualTo(RegistrationStatus.SUBMITTED);
        assertThat(RegistrationStatus.from(OnboardingState.IDENTITY_VERIFIED)).isEqualTo(RegistrationStatus.SUBMITTED);
        assertThat(RegistrationStatus.from(OnboardingState.WALLET_PROVISIONED)).isEqualTo(RegistrationStatus.SUBMITTED);
        assertThat(RegistrationStatus.from(OnboardingState.CREDENTIALS_ISSUED)).isEqualTo(RegistrationStatus.SUBMITTED);
        // ... terminal states map to their outcome; CANCELLED is the cx-ve read-only extension.
        assertThat(RegistrationStatus.from(OnboardingState.COMPLETED)).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(RegistrationStatus.from(OnboardingState.REJECTED)).isEqualTo(RegistrationStatus.DECLINED);
        assertThat(RegistrationStatus.from(OnboardingState.FAILED)).isEqualTo(RegistrationStatus.DECLINED);
        assertThat(RegistrationStatus.from(OnboardingState.CANCELLED)).isEqualTo(RegistrationStatus.CANCELLED);
    }
}
