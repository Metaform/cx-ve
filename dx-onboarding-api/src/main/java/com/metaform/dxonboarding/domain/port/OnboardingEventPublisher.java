package com.metaform.dxonboarding.domain.port;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingCompleted;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStarted;

/**
 * Announces the onboarding lifecycle to observers — the VE's compliance tracker follows a
 * participant from its onboarding's start. Never throws: an event that cannot be published is
 * logged, it does not fail the onboarding it reports on.
 */
public interface OnboardingEventPublisher {

    void onboardingStarted(OnboardingStarted event);

    void onboardingCompleted(OnboardingCompleted event);
}
