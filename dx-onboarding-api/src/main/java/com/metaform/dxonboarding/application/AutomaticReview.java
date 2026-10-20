package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Stands in for the TSP operator's review while the VE has no operator: decides for each new
 * request whether it is approved automatically ({@link ReviewProperties#autoApprove()}) — the
 * requests of participants hosted here, and FOR NOW also those of external participants — or
 * waits in SUBMITTED for a review that does not exist yet.
 *
 * <p>An approval runs on the review worker, after {@code dx-onboarding.review.delay}: the submitting
 * call is acknowledged with SUBMITTED first, as the TSP's API describes, and the applicant reads the
 * outcome back from the request's status. What an approval does is the {@link ApprovalService}'s.
 */
@Component
public class AutomaticReview {

    private static final Logger log = LoggerFactory.getLogger(AutomaticReview.class);

    private final ApprovalService approval;
    private final ReviewProperties properties;
    private final Executor reviewExecutor;

    public AutomaticReview(ApprovalService approval, ReviewProperties properties,
                           @Qualifier("reviewExecutor") Executor reviewExecutor) {
        this.approval = approval;
        this.properties = properties;
        this.reviewExecutor = reviewExecutor;
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
        log.info("onboarding request '{}' of '{}' ({}) is approved automatically", request.id(), request.connectorId(),
                hosting);
        reviewExecutor.execute(() -> approve(request.id()));
    }

    private void approve(String requestId) {
        try {
            if (!properties.delay().isZero()) {
                Thread.sleep(properties.delay().toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        approval.approve(requestId);
    }
}
