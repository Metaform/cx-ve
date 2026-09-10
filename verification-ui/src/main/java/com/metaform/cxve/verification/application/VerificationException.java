package com.metaform.cxve.verification.application;

/**
 * A non-retryable verification failure: the message is the user-facing step detail, so phrase it
 * as a diagnosis ("negotiation reached TERMINATED: ..."), not a stack-trace fragment.
 */
public class VerificationException extends RuntimeException {

    public VerificationException(String message) {
        super(message);
    }

    public VerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
