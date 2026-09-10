package com.metaform.cxve.domain;

import com.metaform.cxve.domain.model.OnboardingState;

/**
 * BEYOND-SPEC: a cancellation (extension DELETE) hit a registration that is already terminal — there is nothing left to cancel,
 * and relabeling a completed or declined outcome would falsify the record. Answered with 409.
 */
public class CancellationNotAllowedException extends RuntimeException {

    public CancellationNotAllowedException(String externalId, OnboardingState state) {
        super("Registration '%s' is already %s and can no longer be cancelled".formatted(externalId, state));
    }
}
