package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingCompleted;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStarted;
import com.metaform.dxonboarding.domain.port.OnboardingEventPublisher;
import java.util.ArrayList;
import java.util.List;

/** Records the announced onboarding lifecycle, in order. */
class RecordingEvents implements OnboardingEventPublisher {

    final List<OnboardingStarted> started = new ArrayList<>();
    final List<OnboardingCompleted> completed = new ArrayList<>();

    @Override
    public void onboardingStarted(OnboardingStarted event) {
        started.add(event);
    }

    @Override
    public void onboardingCompleted(OnboardingCompleted event) {
        completed.add(event);
    }
}
