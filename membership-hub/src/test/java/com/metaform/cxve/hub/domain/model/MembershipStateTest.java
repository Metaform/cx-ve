package com.metaform.cxve.hub.domain.model;

import org.junit.jupiter.api.Test;

import static com.metaform.cxve.hub.domain.model.MembershipState.CONFIRMED;
import static com.metaform.cxve.hub.domain.model.MembershipState.CREDENTIALS_OFFERED;
import static com.metaform.cxve.hub.domain.model.MembershipState.FAILED;
import static com.metaform.cxve.hub.domain.model.MembershipState.PROVISIONED;
import static com.metaform.cxve.hub.domain.model.MembershipState.PROVISIONING;
import static com.metaform.cxve.hub.domain.model.MembershipState.REGISTERING;
import static com.metaform.cxve.hub.domain.model.MembershipState.REJECTED;
import static com.metaform.cxve.hub.domain.model.MembershipState.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition table is the guard every writer consults, so its shape is pinned here: the happy
 * path in the order the hub drives it, the terminal states, and the backwards moves a late or
 * redelivered signal would otherwise apply.
 */
class MembershipStateTest {

    @Test
    void theHostedPathRunsDeploymentFirst() {
        assertThat(PROVISIONING.canAdvanceTo(PROVISIONED)).isTrue();
        assertThat(PROVISIONED.canAdvanceTo(SUBMITTED)).isTrue();
        assertThat(SUBMITTED.canAdvanceTo(CREDENTIALS_OFFERED)).isTrue();
    }

    @Test
    void aMemberWithItsOwnDidStartsAtSubmitted() {
        // Nothing to deploy: its one transition is the registration's outcome.
        assertThat(SUBMITTED.canAdvanceTo(CREDENTIALS_OFFERED)).isTrue();
        assertThat(SUBMITTED.canAdvanceTo(REJECTED)).isTrue();
        assertThat(SUBMITTED.canAdvanceTo(PROVISIONING)).isFalse();
    }

    @Test
    void everyNonTerminalStateCanFail() {
        assertThat(SUBMITTED.canAdvanceTo(FAILED)).isTrue();
        assertThat(PROVISIONING.canAdvanceTo(FAILED)).isTrue();
        assertThat(PROVISIONED.canAdvanceTo(FAILED)).isTrue();
        assertThat(CONFIRMED.canAdvanceTo(FAILED)).isTrue();
    }

    @Test
    void onlyAnUnregisteredMembershipCanBeRejected() {
        assertThat(PROVISIONING.canAdvanceTo(REJECTED)).isFalse();
        assertThat(PROVISIONED.canAdvanceTo(REJECTED)).isFalse();
        assertThat(CONFIRMED.canAdvanceTo(REJECTED)).isFalse();
    }

    @Test
    void legacyStatesStillHeal() {
        // Rows an older hub left behind reach the terminal state on a late or redelivered callback
        // instead of stranding.
        assertThat(REGISTERING.canAdvanceTo(CREDENTIALS_OFFERED)).isTrue();
        assertThat(REGISTERING.canAdvanceTo(REJECTED)).isTrue();
        assertThat(CONFIRMED.canAdvanceTo(CREDENTIALS_OFFERED)).isTrue();
    }

    @Test
    void terminalStatesAreFinal() {
        for (var next : MembershipState.values()) {
            assertThat(CREDENTIALS_OFFERED.canAdvanceTo(next)).isFalse();
            assertThat(REJECTED.canAdvanceTo(next)).isFalse();
            assertThat(FAILED.canAdvanceTo(next)).isFalse();
        }
    }

    @Test
    void backwardsMovesAreNotAdvances() {
        assertThat(PROVISIONED.canAdvanceTo(PROVISIONING)).isFalse();
        assertThat(SUBMITTED.canAdvanceTo(PROVISIONED)).isFalse();
        assertThat(CREDENTIALS_OFFERED.canAdvanceTo(SUBMITTED)).isFalse();
    }
}
