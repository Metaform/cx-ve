package com.metaform.cxve.verification.application;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Minimal awaitility replacement for the ported e2e wait loops: {@code attempt} is retried while
 * it throws {@link RetryException} (the "not there yet / transient 5xx" signal), every other
 * exception aborts immediately (the e2e suite's IllegalStateException-aborts-awaitility
 * contract), and exhausting the timeout raises a {@link VerificationException} carrying the last
 * retry reason.
 */
public final class Poller {

    private Poller() {
    }

    public static <T> T poll(String what, Duration timeout, Duration interval, Supplier<T> attempt) {
        var deadline = Instant.now().plus(timeout);
        RetryException last = null;
        while (true) {
            try {
                return attempt.get();
            } catch (RetryException e) {
                last = e;
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new VerificationException("timed out after %s waiting for %s%s".formatted(
                        timeout, what, last == null ? "" : ": " + last.getMessage()), last);
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VerificationException("interrupted while waiting for " + what, e);
            }
        }
    }

    /** Fails the current attempt only — the poll retries until its timeout. */
    public static class RetryException extends RuntimeException {

        public RetryException(String message) {
            super(message);
        }

        public RetryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
