package com.metaform.cxve.domain;

/**
 * A tenant registration reused an {@code externalId} the same OSP already submitted one under —
 * answered with 409 per CX-0009 §2.2.2. Every earlier attempt blocks the id (in flight, declined,
 * failed or completed — the spec's "already exists" carries no state qualifier) EXCEPT a
 * cancelled one: cancellation exists precisely so the OSP can resubmit corrected data under the
 * same correlation id.
 */
public class DuplicateRegistrationException extends RuntimeException {

    public DuplicateRegistrationException(String externalId) {
        super("A registration request already exists with externalId '%s' for this OSP".formatted(externalId));
    }
}
