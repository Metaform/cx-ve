package com.metaform.dxonboarding.domain;

import java.time.Instant;

/** A received application and what became of it; {@code decision} is null while undecided. */
public record ApplicationRecord(String applicationId, String submitter, MembershipApplication application,
                                Instant receivedAt, Decision decision) {

    public ApplicationRecord decided(Decision decision) {
        return new ApplicationRecord(applicationId, submitter, application, receivedAt, decision);
    }
}
