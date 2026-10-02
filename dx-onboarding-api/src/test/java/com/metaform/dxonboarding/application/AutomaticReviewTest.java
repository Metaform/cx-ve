package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.adapter.out.persistence.InMemoryOnboardingRequestRepository;
import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.ReviewDecision;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stand-in for the TSP operator: participants hosted here are approved automatically, external
 * ones as configured (for now: approved too), and an approval never overwrites a decision.
 */
class AutomaticReviewTest {

    private static final Instant DECIDED_AT = Instant.parse("2026-10-02T10:00:05Z");
    private static final String HOSTED_PREFIX = "did:web:identity.cxve.localhost:";
    private static final String HOSTED = HOSTED_PREFIX + "verification-participant-dx";
    private static final String EXTERNAL = "did:web:sut.example.com";

    private final InMemoryOnboardingRequestRepository repository = new InMemoryOnboardingRequestRepository();
    private final List<Runnable> pending = new ArrayList<>();

    private AutomaticReview review(boolean hosted, boolean external) {
        return new AutomaticReview(repository,
                new ReviewProperties(HOSTED_PREFIX, new ReviewProperties.AutoApprove(hosted, external), null),
                pending::add, Clock.fixed(DECIDED_AT, ZoneOffset.UTC));
    }

    private OnboardingRequest filed(String id, String connectorId) {
        var request = new OnboardingRequest(id, "DX-OR-000001", connectorId, Instant.parse("2026-10-02T10:00:00Z"),
                OnboardingStatus.SUBMITTED, null, null, null, null, null, null, Map.of(), null);
        repository.save(request);
        return request;
    }

    private OnboardingRequest stored(String id) {
        return repository.findById(id).orElseThrow();
    }

    private void runWorker() {
        pending.forEach(Runnable::run);
        pending.clear();
    }

    @Test
    void aParticipantHostedHere_isApprovedAfterTheSubmission() {
        review(true, false).submitted(filed("req-1", HOSTED));

        // acknowledged as SUBMITTED first: the approval runs on the worker, after the submission
        assertThat(stored("req-1").status()).isEqualTo(OnboardingStatus.SUBMITTED);
        runWorker();

        var approved = stored("req-1");
        assertThat(approved.status()).isEqualTo(OnboardingStatus.APPROVED);
        assertThat(approved.decision()).isEqualTo(new ReviewDecision(DECIDED_AT, null, null, null));
        assertThat(approved.legalEntityId()).isNotBlank();
    }

    @Test
    void anExternalParticipant_isApprovedOnlyWhileExternalAutoApprovalIsOn() {
        review(true, true).submitted(filed("req-1", EXTERNAL));
        review(true, false).submitted(filed("req-2", EXTERNAL));
        runWorker();

        assertThat(stored("req-1").status()).isEqualTo(OnboardingStatus.APPROVED);
        assertThat(stored("req-2").status()).isEqualTo(OnboardingStatus.SUBMITTED);
        assertThat(stored("req-2").decision()).isNull();
    }

    @Test
    void aParticipantHostedHere_waitsWhenHostedAutoApprovalIsOff() {
        review(false, true).submitted(filed("req-1", HOSTED));
        runWorker();

        assertThat(stored("req-1").status()).isEqualTo(OnboardingStatus.SUBMITTED);
    }

    @Test
    void anApproval_neverOverwritesADecisionTakenInTheMeantime() {
        review(true, true).submitted(filed("req-1", HOSTED));
        var rejected = new OnboardingRequest("req-1", "DX-OR-000001", HOSTED, Instant.parse("2026-10-02T10:00:00Z"),
                OnboardingStatus.REJECTED, null, null, null, null, null, null, Map.of(),
                new ReviewDecision(DECIDED_AT, ReviewDecision.RejectReasonCode.OTHER, "no", false));
        repository.save(rejected);

        runWorker();

        assertThat(stored("req-1")).isEqualTo(rejected);
    }

    @Test
    void withoutAHostedPrefix_noParticipantCountsAsHostedHere() {
        var properties = new ReviewProperties(" ", new ReviewProperties.AutoApprove(true, false), null);

        assertThat(properties.hostedHere(HOSTED)).isFalse();
        assertThat(new ReviewProperties(HOSTED_PREFIX, null, null).hostedHere(HOSTED)).isTrue();
        assertThat(new ReviewProperties(HOSTED_PREFIX, null, null).hostedHere(EXTERNAL)).isFalse();
    }
}
