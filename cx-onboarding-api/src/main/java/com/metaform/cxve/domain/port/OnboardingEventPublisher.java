package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingStarted;

/**
 * Announces onboarding lifecycle events to the outside world.
 *
 * <p>Publishing is best-effort and must never fail the onboarding it describes: implementations
 * swallow transport errors, because by the time an event is raised the process has already been
 * accepted and failing the caller would misreport work that is under way.
 */
public interface OnboardingEventPublisher {

    /** Announces that an onboarding process has started. */
    void onboardingStarted(OnboardingStarted event);

    /** Announces that an onboarding process completed successfully. */
    void onboardingCompleted(OnboardingCompleted event);
}
