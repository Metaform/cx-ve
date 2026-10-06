package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stand-in for the TSP operator: participants hosted here are approved automatically, external
 * ones as configured (for now: approved too) — always after the submission was acknowledged.
 */
class AutomaticReviewTest {

    private static final String HOSTED_PREFIX = "did:web:identity.cxve.localhost:";
    private static final String HOSTED = HOSTED_PREFIX + "verification-participant-dx";
    private static final String EXTERNAL = "did:web:sut.example.com";

    private final List<Runnable> pending = new ArrayList<>();
    private final List<String> approved = new ArrayList<>();
    private final ApprovalService approval = new ApprovalService(null, null, null, null, null) {
        @Override
        public void approve(String requestId) {
            approved.add(requestId);
        }
    };

    private AutomaticReview review(boolean hosted, boolean external) {
        return new AutomaticReview(approval,
                new ReviewProperties(HOSTED_PREFIX, new ReviewProperties.AutoApprove(hosted, external), null),
                pending::add);
    }

    private static OnboardingRequest filed(String id, String connectorId) {
        return new OnboardingRequest(id, "DX-OR-000001", connectorId, Instant.parse("2026-10-02T10:00:00Z"),
                OnboardingStatus.SUBMITTED, null, null, null, null, null, null, Map.of(), null);
    }

    private void runWorker() {
        pending.forEach(Runnable::run);
        pending.clear();
    }

    @Test
    void aParticipantHostedHere_isApprovedAfterTheSubmission() {
        review(true, false).submitted(filed("req-1", HOSTED));

        // acknowledged as SUBMITTED first: the approval runs on the worker, after the submission
        assertThat(approved).isEmpty();
        runWorker();
        assertThat(approved).containsExactly("req-1");
    }

    @Test
    void anExternalParticipant_isApprovedOnlyWhileExternalAutoApprovalIsOn() {
        review(true, true).submitted(filed("req-1", EXTERNAL));
        review(true, false).submitted(filed("req-2", EXTERNAL));
        runWorker();

        assertThat(approved).containsExactly("req-1");
    }

    @Test
    void aParticipantHostedHere_waitsWhenHostedAutoApprovalIsOff() {
        review(false, true).submitted(filed("req-1", HOSTED));
        runWorker();

        assertThat(approved).isEmpty();
    }

    @Test
    void withoutAHostedPrefix_noParticipantCountsAsHostedHere() {
        var properties = new ReviewProperties(" ", new ReviewProperties.AutoApprove(true, false), null);

        assertThat(properties.hostedHere(HOSTED)).isFalse();
        assertThat(new ReviewProperties(HOSTED_PREFIX, null, null).hostedHere(HOSTED)).isTrue();
        assertThat(new ReviewProperties(HOSTED_PREFIX, null, null).hostedHere(EXTERNAL)).isFalse();
    }
}
