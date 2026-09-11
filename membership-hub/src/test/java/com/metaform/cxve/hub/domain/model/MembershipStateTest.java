package com.metaform.cxve.hub.domain.model;

import org.junit.jupiter.api.Test;

import static com.metaform.cxve.hub.domain.model.MembershipState.CONFIRMED;
import static com.metaform.cxve.hub.domain.model.MembershipState.FAILED;
import static com.metaform.cxve.hub.domain.model.MembershipState.PROVISIONED;
import static com.metaform.cxve.hub.domain.model.MembershipState.PROVISIONING;
import static com.metaform.cxve.hub.domain.model.MembershipState.REGISTERING;
import static com.metaform.cxve.hub.domain.model.MembershipState.REJECTED;
import static com.metaform.cxve.hub.domain.model.MembershipState.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

/** The monotonic transition table — the guard every state-carrying signal passes through. */
class MembershipStateTest {

    @Test
    void theHappyPathAdvances() {
        assertThat(SUBMITTED.canAdvanceTo(CONFIRMED)).isTrue();
        assertThat(CONFIRMED.canAdvanceTo(PROVISIONING)).isTrue();
        assertThat(PROVISIONING.canAdvanceTo(PROVISIONED)).isTrue();
    }

    @Test
    void failureAndRejectionAreReachableWhereTheyCanHappen() {
        assertThat(SUBMITTED.canAdvanceTo(REJECTED)).isTrue();
        assertThat(SUBMITTED.canAdvanceTo(FAILED)).isTrue();
        assertThat(CONFIRMED.canAdvanceTo(FAILED)).isTrue();
        assertThat(PROVISIONING.canAdvanceTo(FAILED)).isTrue();
        // a DECLINED after confirmation is contradictory input, not a transition
        assertThat(CONFIRMED.canAdvanceTo(REJECTED)).isFalse();
    }

    @Test
    void legacyRegisteringRecordsCanStillHeal() {
        assertThat(REGISTERING.canAdvanceTo(CONFIRMED)).isTrue();
        assertThat(REGISTERING.canAdvanceTo(REJECTED)).isTrue();
    }

    @Test
    void nothingMovesBackwardsAndTerminalsAreFinal() {
        assertThat(PROVISIONING.canAdvanceTo(CONFIRMED)).isFalse();
        assertThat(CONFIRMED.canAdvanceTo(SUBMITTED)).isFalse();
        for (var terminal : new MembershipState[] { PROVISIONED, REJECTED, FAILED }) {
            for (var next : MembershipState.values()) {
                assertThat(terminal.canAdvanceTo(next)).as("%s -> %s", terminal, next).isFalse();
            }
        }
    }
}
