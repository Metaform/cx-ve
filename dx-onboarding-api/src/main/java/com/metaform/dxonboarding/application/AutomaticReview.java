package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.ChangeRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.port.OnboardingRequestRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Stands in for the TSP operator's review while the VE has no operator: decides for each new
 * request whether it is reviewed automatically ({@link ReviewProperties#autoApprove()}) — the
 * requests of participants hosted here, and FOR NOW also those of external participants — or
 * waits in SUBMITTED for a review that does not exist yet.
 *
 * <p>An automatic review takes the request on ({@code UNDER_REVIEW}) and approves it — after asking
 * for changes once, where {@link ReviewProperties#requestChanges()} says so: the request is handed
 * back to the applicant ({@code CHANGES_REQUESTED}) with a {@link ChangeRequest} that asks for
 * nothing, and approved once the applicant submitted it again. That is the round a real review may
 * ask of a participant, which the VE has every participant go through.
 *
 * <p>The review runs on the review worker, each step after {@code dx-onboarding.review.delay}: the
 * submitting call is acknowledged first, as the TSP's API describes, and the applicant reads the
 * outcome back from the request's status. What an approval does is the {@link ApprovalService}'s.
 */
@Component
public class AutomaticReview {

    /** What the automatic review asks: nothing, but that the request be submitted again. */
    static final String CHANGE_REQUEST_COMMENT = "Automatic review of the verification environment: every onboarding "
            + "request is asked for changes once. Nothing needs to be corrected — submit the request again "
            + "(POST /{requestId}/submit) to have it approved.";

    private static final Logger log = LoggerFactory.getLogger(AutomaticReview.class);

    private final ApprovalService approval;
    private final OnboardingRequestRepository repository;
    private final ReviewProperties properties;
    private final Executor reviewExecutor;
    private final Clock clock;

    @Autowired
    public AutomaticReview(ApprovalService approval, OnboardingRequestRepository repository,
                           ReviewProperties properties, @Qualifier("reviewExecutor") Executor reviewExecutor) {
        this(approval, repository, properties, reviewExecutor, Clock.systemUTC());
    }

    AutomaticReview(ApprovalService approval, OnboardingRequestRepository repository, ReviewProperties properties,
                    Executor reviewExecutor, Clock clock) {
        this.approval = approval;
        this.repository = repository;
        this.properties = properties;
        this.reviewExecutor = reviewExecutor;
        this.clock = clock;
    }

    /** Takes a newly submitted request into review. */
    public void submitted(OnboardingRequest request) {
        var hostedHere = properties.hostedHere(request.connectorId());
        var hosting = hostedHere ? "hosted here" : "external";
        var autoApproved = hostedHere ? properties.autoApprove().hosted() : properties.autoApprove().external();
        if (!autoApproved) {
            log.info("onboarding request '{}' of '{}' ({}) awaits a TSP operator's review", request.id(),
                    request.connectorId(), hosting);
            return;
        }
        var changesFirst = hostedHere ? properties.requestChanges().hosted() : properties.requestChanges().external();
        log.info("onboarding request '{}' of '{}' ({}) is reviewed automatically: {}", request.id(),
                request.connectorId(), hosting, changesFirst ? "asked for changes once, then approved" : "approved");
        reviewExecutor.execute(() -> review(request.id(), changesFirst));
    }

    /**
     * Takes a request back into review, which its applicant submitted again after a change request.
     * Only the automatic review asks for changes, and only once: the request is approved.
     */
    public void resubmitted(OnboardingRequest request) {
        log.info("onboarding request '{}' of '{}' was submitted again: it is approved automatically", request.id(),
                request.connectorId());
        reviewExecutor.execute(() -> {
            if (waited()) {
                approval.approve(request.id());
            }
        });
    }

    private void review(String requestId, boolean changesFirst) {
        if (!waited() || takeOn(requestId).isEmpty()) {
            return;
        }
        if (changesFirst) {
            requestChanges(requestId);
        } else {
            approval.approve(requestId);
        }
    }

    /** Assigns the reviewer: SUBMITTED becomes UNDER_REVIEW. Empty when the request is no longer there to review. */
    private synchronized Optional<OnboardingRequest> takeOn(String requestId) {
        var underReview = repository.findById(requestId)
                .filter(request -> request.status() == OnboardingStatus.SUBMITTED)
                .map(OnboardingRequest::underReview);
        underReview.ifPresent(repository::save);
        return underReview;
    }

    /** Hands the request back to its applicant: UNDER_REVIEW becomes CHANGES_REQUESTED. */
    private synchronized void requestChanges(String requestId) {
        repository.findById(requestId)
                .filter(request -> request.status() == OnboardingStatus.UNDER_REVIEW)
                .map(request -> request.changesRequested(
                        ChangeRequest.of(clock.instant(), CHANGE_REQUEST_COMMENT, List.of())))
                .ifPresent(changesRequested -> {
                    repository.save(changesRequested);
                    log.info("onboarding request '{}' ({}) CHANGES_REQUESTED: handed back to '{}', to be submitted again",
                            changesRequested.id(), changesRequested.businessId(), changesRequested.connectorId());
                });
    }

    /** Waits for the review's delay; false when interrupted, which ends the review. */
    private boolean waited() {
        try {
            if (!properties.delay().isZero()) {
                Thread.sleep(properties.delay().toMillis());
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
