package com.metaform.cxve.domain;

/**
 * A tenant registration reused an {@code externalId} the same OSP already submitted one under —
 * answered with 409 per CX-0009 §2.2.2. The check is strict (any state, including declined
 * attempts): the spec's "already exists" carries no state qualifier, and OSPs mint a fresh id
 * per registration anyway.
 */
public class DuplicateRegistrationException extends RuntimeException {

    public DuplicateRegistrationException(String externalId) {
        super("A registration request already exists with externalId '%s' for this OSP".formatted(externalId));
    }
}
