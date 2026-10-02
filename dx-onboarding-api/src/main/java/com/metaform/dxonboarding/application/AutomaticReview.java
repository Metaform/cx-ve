package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.port.OnboardingRequestRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * approval back from the request's status.
 */
@Component
public class AutomaticReview {

    private static final Logger log = LoggerFactory.getLogger(AutomaticReview.class);

    private final OnboardingRequestRepository repository;
    private final ReviewProperties properties;
    private final Executor reviewExecutor;
    private final Clock clock;

    @Autowired
    public AutomaticReview(OnboardingRequestRepository repository, ReviewProperties properties,
                           @Qualifier("reviewExecutor") Executor reviewExecutor) {
        this(repository, properties, reviewExecutor, Clock.systemUTC());
    }

    AutomaticReview(OnboardingRequestRepository repository, ReviewProperties properties, Executor reviewExecutor,
                    Clock clock) {
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
        // only a request still awaiting review: nothing else decides one today, but an approval
        // must never overwrite a decision taken in the meantime
        repository.findById(requestId)
                .filter(request -> request.status() == OnboardingStatus.SUBMITTED)
                .map(request -> request.approved(clock.instant(), UUID.randomUUID().toString()))
                .ifPresent(approved -> {
                    repository.save(approved);
                    log.info("onboarding request '{}' ({}) APPROVED", approved.id(), approved.businessId());
                });
    }
}
