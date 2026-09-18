package com.metaform.cxve.verification.domain.model;

import java.util.List;

/**
 * The steps a verification run can take — CX-0135 v3.0.0 Flow B (provider-initiated push) either
 * way, with the verification participant as the certificate CONSUMER and the participant-under-test
 * as the PROVIDER. Which steps a run actually has depends on where that participant lives, so the
 * two sequences are declared here rather than assumed by the flows:
 *
 * <ul>
 *   <li>{@link #MANAGED} — the participant is onboarded INTO this environment, which then drives
 *       both sides through its own management APIs.</li>
 *   <li>{@link #EXTERNAL} — the participant is a third-party system already running elsewhere,
 *       reachable only over DSP and DCP. This environment drives its own side and WAITS for the
 *       system under test to do the rest: request its credentials, offer its certificate asset,
 *       and push the certificate.</li>
 * </ul>
 */
public enum RunStep {

    /** Both: the permanent verification participant exists and its inbox offer is seeded. */
    ENSURE_VERIFICATION_PARTICIPANT,

    /** External: the declared DID resolves and advertises the endpoints the exchange needs. */
    RESOLVE_DID,

    ONBOARD_PARTICIPANT,

    /** Managed: the participant's EDC resources are provisioned and its context id is known. */
    AWAIT_PROVISIONED,

    /** External: the hub has had the issuer offer the membership credentials to the SUT. */
    AWAIT_CREDENTIAL_OFFER,

    /**
     * Both: the participant's wallet requested the offered credentials and the issuer delivered
     * them — visible as a delivery in the ledger, and a precondition for every DSP message that
     * follows. Every member is offered its credentials over DCP, so a participant provisioned here
     * waits for this just as a third-party system does.
     */
    AWAIT_CREDENTIALS,

    /** Managed: the participant's certificate tenant is up. */
    AWAIT_CERTO_CONTEXT,

    /** Managed: this environment seeds the participant's certificate offer on its behalf. */
    SEED_PROVIDER_OFFER,

    /**
     * Both: the verification participant becomes DSP consumer of the participant-under-test's
     * certificate asset. For an external run this also WAITS for the SUT to publish that offer.
     */
    ESTABLISH_PULL_FLOW,

    /** Managed: the participant-under-test consumes the verification participant's inbox asset. */
    ESTABLISH_PUSH_FLOW,

    /** Managed: this environment publishes the certificate on the participant's behalf. */
    PUBLISH_CERTIFICATE,

    /** External: the SUT pushed a certificate, found through the consumer's reconciliation query. */
    AWAIT_PUBLISHED_CERTIFICATE,

    RETRIEVE_AND_VERIFY,
    ACCEPT,
    EVALUATE_EVENTS;

    /** The run whose participant this environment onboards and drives itself. */
    public static final List<RunStep> MANAGED = List.of(
            ENSURE_VERIFICATION_PARTICIPANT,
            ONBOARD_PARTICIPANT,
            AWAIT_PROVISIONED,
            AWAIT_CREDENTIALS,
            AWAIT_CERTO_CONTEXT,
            SEED_PROVIDER_OFFER,
            ESTABLISH_PULL_FLOW,
            ESTABLISH_PUSH_FLOW,
            PUBLISH_CERTIFICATE,
            RETRIEVE_AND_VERIFY,
            ACCEPT,
            EVALUATE_EVENTS);

    /**
     * The run against a third-party system. Two steps of the managed sequence have no counterpart
     * here. There is no push-flow step: the SUT establishes that flow itself, and the certificate
     * arriving over it ({@link #AWAIT_PUBLISHED_CERTIFICATE}) is the only evidence of it this
     * environment can honestly observe. And there is no closing ledger step: everything the
     * tracker can attribute to an external participant — its onboarding and its credential
     * delivery — must be true BEFORE the exchange can work at all, so the ledger is judged at
     * {@link #AWAIT_CREDENTIALS} and re-reading it afterwards would add nothing.
     */
    public static final List<RunStep> EXTERNAL = List.of(
            ENSURE_VERIFICATION_PARTICIPANT,
            RESOLVE_DID,
            ONBOARD_PARTICIPANT,
            AWAIT_CREDENTIAL_OFFER,
            AWAIT_CREDENTIALS,
            ESTABLISH_PULL_FLOW,
            AWAIT_PUBLISHED_CERTIFICATE,
            RETRIEVE_AND_VERIFY,
            ACCEPT);
}
