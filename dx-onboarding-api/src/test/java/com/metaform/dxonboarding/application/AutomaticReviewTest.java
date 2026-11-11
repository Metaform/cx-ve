package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.adapter.out.persistence.InMemoryOnboardingRequestRepository;
import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.Requests;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stand-in for the TSP operator: participants hosted here are reviewed automatically, external
 * ones as configured (for now: reviewed too) — always after the submission was acknowledged. An
 * automatic review approves the request, after asking for changes once where configured.
 */
class AutomaticReviewTest {

    private static final String HOSTED_PREFIX = "did:web:identity.cxve.localhost:";
    private static final String HOSTED = HOSTED_PREFIX + "verification-participant-dx";
    private static final String EXTERNAL = "did:web:sut.example.com";
    private static final Instant REVIEWED_AT = Instant.parse("2026-10-02T10:00:05Z");

    private final InMemoryOnboardingRequestRepository repository = new InMemoryOnboardingRequestRepository();
    private final List<Runnable> pending = new ArrayList<>();
    /** The requests approved, each with the status it was approved in. */
    private final List<String> approved = new ArrayList<>();
    private final ApprovalService approval = new ApprovalService(null, null, null, null, null) {
        @Override
        public void approve(String requestId) {
            approved.add(requestId + ":" + repository.findById(requestId).orElseThrow().status());
        }
    };

    /** A review that approves as given, without asking for changes. */
    private AutomaticReview review(boolean hosted, boolean external) {
        return review(new ReviewProperties.AutoApprove(hosted, external), new ReviewProperties.RequestChanges(false, false));
    }

    private AutomaticReview review(ReviewProperties.AutoApprove autoApprove, ReviewProperties.RequestChanges requestChanges) {
        return new AutomaticReview(approval, repository,
                new ReviewProperties(HOSTED_PREFIX, autoApprove, requestChanges, null), pending::add,
                Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
    }

    private OnboardingRequest filed(String id, String connectorId) {
        var request = OnboardingRequest.draft(id, "DX-OR-000001", connectorId, Requests.data(connectorId), null,
                Instant.parse("2026-10-02T10:00:00Z")).submitted(Instant.parse("2026-10-02T10:00:01Z"), null);
        repository.save(request);
        return request;
    }

    private OnboardingRequest stored(String id) {
        return repository.findById(id).orElseThrow();
    }

    private void runWorker() {
        var due = List.copyOf(pending);
        pending.clear();
        due.forEach(Runnable::run);
    }

    @Test
    void aParticipantHostedHere_isApprovedAfterTheSubmission_onceUnderReview() {
        review(true, false).submitted(filed("req-1", HOSTED));

        // acknowledged as SUBMITTED first: the review runs on the worker, after the submission
        assertThat(approved).isEmpty();
        assertThat(stored("req-1").status()).isEqualTo(OnboardingStatus.SUBMITTED);
        runWorker();
        assertThat(approved).containsExactly("req-1:UNDER_REVIEW");
    }

    @Test
    void anExternalParticipant_isApprovedOnlyWhileExternalAutoApprovalIsOn() {
        review(true, true).submitted(filed("req-1", EXTERNAL));
        review(true, false).submitted(filed("req-2", EXTERNAL));
        runWorker();

        assertThat(approved).containsExactly("req-1:UNDER_REVIEW");
        assertThat(stored("req-2").status()).isEqualTo(OnboardingStatus.SUBMITTED);
    }

    @Test
    void aParticipantHostedHere_waitsWhenHostedAutoApprovalIsOff() {
        review(false, true).submitted(filed("req-1", HOSTED));
        runWorker();

        assertThat(approved).isEmpty();
    }

    @Test
    void aRequest_isAskedForChangesOnce_andApprovedWhenSubmittedAgain() {
        var review = review(new ReviewProperties.AutoApprove(true, true), new ReviewProperties.RequestChanges(true, true));
        var filed = filed("req-1", EXTERNAL);

        review.submitted(filed);
        runWorker();

        var asked = stored("req-1");
        assertThat(approved).isEmpty();
        assertThat(asked.status()).isEqualTo(OnboardingStatus.CHANGES_REQUESTED);
        // taken on, then handed back: two steps of the review
        assertThat(asked.version()).isEqualTo(filed.version() + 2);
        assertThat(asked.changeRequest().requestedAt()).isEqualTo(REVIEWED_AT);
        assertThat(asked.changeRequest().comment()).isEqualTo(AutomaticReview.CHANGE_REQUEST_COMMENT);
        assertThat(asked.changeRequest().items()).isEmpty();
        assertThat(asked.changeRequest().resubmittedAt()).isNull();

        var resubmitted = asked.resubmitted(REVIEWED_AT.plusSeconds(60), null);
        repository.save(resubmitted);
        review.resubmitted(resubmitted);

        assertThat(approved).isEmpty();
        runWorker();
        assertThat(approved).containsExactly("req-1:UNDER_REVIEW");
    }

    @Test
    void changesAreRequested_ofHostedAndExternalParticipantsAsConfigured_andOnlyOfThoseReviewedAutomatically() {
        var hostedOnly = review(new ReviewProperties.AutoApprove(true, true), new ReviewProperties.RequestChanges(true, false));
        hostedOnly.submitted(filed("hosted", HOSTED));
        hostedOnly.submitted(filed("external", EXTERNAL));
        // not reviewed automatically: it awaits an operator, who alone could ask it for changes
        review(new ReviewProperties.AutoApprove(false, false), new ReviewProperties.RequestChanges(true, true))
                .submitted(filed("waiting", EXTERNAL));
        runWorker();

        assertThat(stored("hosted").status()).isEqualTo(OnboardingStatus.CHANGES_REQUESTED);
        assertThat(approved).containsExactly("external:UNDER_REVIEW");
        assertThat(stored("waiting").status()).isEqualTo(OnboardingStatus.SUBMITTED);
    }

    @Test
    void aRequestNoLongerSubmitted_isLeftAlone() {
        var review = review(new ReviewProperties.AutoApprove(true, true), new ReviewProperties.RequestChanges(true, true));
        var filed = filed("req-1", EXTERNAL);
        review.submitted(filed);
        var rejected = filed.rejected(REVIEWED_AT, null, "decided otherwise", false);
        repository.save(rejected);

        runWorker();

        assertThat(stored("req-1")).isEqualTo(rejected);
        assertThat(approved).isEmpty();
    }

    @Test
    void withoutAHostedPrefix_noParticipantCountsAsHostedHere() {
        var properties = new ReviewProperties(" ", new ReviewProperties.AutoApprove(true, false), null, null);

        assertThat(properties.hostedHere(HOSTED)).isFalse();
        assertThat(new ReviewProperties(HOSTED_PREFIX, null, null, null).hostedHere(HOSTED)).isTrue();
        assertThat(new ReviewProperties(HOSTED_PREFIX, null, null, null).hostedHere(EXTERNAL)).isFalse();
        // nobody is asked for changes unless configured
        assertThat(properties.requestChanges()).isEqualTo(new ReviewProperties.RequestChanges(false, false));
    }
}
