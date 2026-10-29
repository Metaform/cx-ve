package com.metaform.cxve.verification.domain.model;

import java.util.List;

/**
 * The steps a verification run can take. So far there is one verifiable use case, CCM — a
 * consumer-initiated certificate request as the DSAF SCE specification defines it (Certo's
 * current release), either way with the verification participant as the certificate CONSUMER and
 * the participant-under-test as the PROVIDER. Which steps a run actually has depends on where that
 * participant lives, so the two sequences are declared here rather than assumed by the flows:
 *
 * <ul>
 *   <li>{@link #MANAGED} — the participant is onboarded INTO this environment, which then drives
 *       both sides through its own management APIs.</li>
 *   <li>{@link #EXTERNAL} — the participant is a third-party system already running elsewhere,
 *       reachable only over DSP and DCP. This environment drives its own side and WAITS for the
 *       system under test to do the rest: request its credentials, offer its provider API, and
 *       hold (or issue) the certificate this environment requests.</li>
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

    /** External: the SUT's registration has had the issuer offer it the membership credentials. */
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

    /** Managed: this environment issues the certificate on the participant's behalf. */
    ISSUE_CERTIFICATE,

    /** Managed: the participant-under-test notifies the verification participant it is AVAILABLE. */
    NOTIFY_AVAILABLE,

    /**
     * Both: the verification participant requests the certificate from the participant-under-test.
     * For an external run this also WAITS for the SUT to fulfil the request if it does not hold the
     * certificate yet.
     */
    REQUEST_CERTIFICATE,

    RETRIEVE_AND_VERIFY,
    ACCEPT,
    EVALUATE_EVENTS;

    /** The CCM run whose participant this environment onboards and drives itself. */
    public static final List<RunStep> MANAGED = List.of(
            ENSURE_VERIFICATION_PARTICIPANT,
            ONBOARD_PARTICIPANT,
            AWAIT_PROVISIONED,
            AWAIT_CREDENTIALS,
            AWAIT_CERTO_CONTEXT,
            SEED_PROVIDER_OFFER,
            ESTABLISH_PULL_FLOW,
            ESTABLISH_PUSH_FLOW,
            ISSUE_CERTIFICATE,
            NOTIFY_AVAILABLE,
            REQUEST_CERTIFICATE,
            RETRIEVE_AND_VERIFY,
            ACCEPT,
            EVALUATE_EVENTS);

    /**
     * The run against a third-party system. The managed sequence's provider-side steps have no
     * counterpart here: issuing the certificate and notifying about it are the SUT's own business,
     * and a request this environment places ({@link #REQUEST_CERTIFICATE}) is what it can honestly
     * observe of them. Nor is there a push flow: notifications to this environment are optional, so
     * nothing here depends on the SUT establishing one. And there is no closing ledger step: everything the
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
            REQUEST_CERTIFICATE,
            RETRIEVE_AND_VERIFY,
            ACCEPT);
}
