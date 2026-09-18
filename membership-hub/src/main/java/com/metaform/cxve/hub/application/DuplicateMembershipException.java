package com.metaform.cxve.hub.application;

/**
 * A member whose DID or BPN a live membership already holds. Raised BEFORE anything is deployed or
 * submitted: the Onboarding API would decline the duplicate registration anyway, and by then a
 * member hosted here would already have its EDC resources, which nothing takes back.
 *
 * <p>Only what this hub can see is checked. A DID registered through another route is still caught
 * downstream — as a declined registration, with the resources left behind.
 */
public class DuplicateMembershipException extends RuntimeException {

    public DuplicateMembershipException(String message) {
        super(message);
    }
}
