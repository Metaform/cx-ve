package com.metaform.cxve.verification.domain.model;

/**
 * The steps of a verification run, in execution order — CX-0135 v3.0.0 Flow B (provider-initiated
 * push), mirroring the e2e suite's {@code certificateExchange()}: the verification participant is
 * the certificate CONSUMER, the participant-under-test the certificate PROVIDER.
 */
public enum RunStep {
    ENSURE_VERIFICATION_PARTICIPANT,
    ONBOARD_PARTICIPANT,
    AWAIT_PROVISIONED,
    AWAIT_CERTO_CONTEXT,
    SEED_PROVIDER_OFFER,
    ESTABLISH_PULL_FLOW,
    ESTABLISH_PUSH_FLOW,
    PUBLISH_CERTIFICATE,
    RETRIEVE_AND_VERIFY,
    ACCEPT,
    EVALUATE_EVENTS
}
