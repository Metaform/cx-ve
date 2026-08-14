package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingStarted;
import com.metaform.cxve.domain.port.OnboardingEventPublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures published onboarding events so tests can assert on them without a broker.
 */
class RecordingOnboardingEventPublisher implements OnboardingEventPublisher {

    /** Test-only DID template, mirroring the shape of the deployed one. */
    static final String DID_TEMPLATE = "did:web:identity.test:";

    private final List<OnboardingStarted> started = new ArrayList<>();
    private final List<OnboardingCompleted> completed = new ArrayList<>();

    static ParticipantDidResolver didResolver() {
        return new ParticipantDidResolver(DID_TEMPLATE);
    }

    @Override
    public void onboardingStarted(OnboardingStarted event) {
        started.add(event);
    }

    @Override
    public void onboardingCompleted(OnboardingCompleted event) {
        completed.add(event);
    }

    List<OnboardingStarted> started() {
        return List.copyOf(started);
    }

    List<OnboardingCompleted> completed() {
        return List.copyOf(completed);
    }
}
