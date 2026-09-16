package com.metaform.cxve.hub.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One partner's membership as it moves through {@link MembershipState} — and the correlation
 * record between the two id spaces this app bridges: {@code externalId} is minted here and is the
 * key the Onboarding API's status callbacks carry; {@code participantContextId} (with
 * {@code tenantId}/{@code participantProfileId}) is what the Tenant Manager's provisioning
 * assigns. Both live on this one record, keyed by the {@code externalId}.
 *
 * <p>The DID is resolved at submission (caller-supplied or template-derived) and is what the
 * registration runs under AND what the participant profile is deployed as — the two legs agree by
 * construction.
 *
 * <p>{@code externallyHosted} members are the exception to all of that: their participant
 * resources live outside this environment, so nothing is provisioned here and
 * {@code tenantId}/{@code participantProfileId}/{@code participantContextId} stay null for the
 * record's whole life. The flag is on the wire precisely so a client can tell that absence apart
 * from "not provisioned yet".
 *
 * <p>Immutable — each transition returns a new instance via the {@code with*} helpers. The
 * {@code version} is the optimistic-lock token of the snapshot this instance was loaded from
 * (null before the first persist): saving compares it against the stored row, so two writers —
 * the submitting thread, the callback, the provisioning worker — cannot silently overwrite each
 * other. Persistence-internal, never serialized to clients.
 */
public record Membership(
        String externalId,
        String name,
        String did,
        String bpn,
        MembershipState state,
        String onboardingProcessId,
        String tenantId,
        String participantProfileId,
        String participantContextId,
        String failureReason,
        @JsonIgnore Long version,
        boolean externallyHosted
) {

    public static Membership submitted(String externalId, String name, String did, String bpn,
                                       boolean externallyHosted) {
        return new Membership(externalId, name, did, bpn, MembershipState.SUBMITTED,
                null, null, null, null, null, null, externallyHosted);
    }

    /** A membership whose participant resources this environment provisions — the common case. */
    public static Membership submitted(String externalId, String name, String did, String bpn) {
        return submitted(externalId, name, did, bpn, false);
    }

    public Membership withState(MembershipState newState) {
        return new Membership(externalId, name, did, bpn, newState, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version, externallyHosted);
    }

    public Membership withOnboardingProcessId(String processId) {
        return new Membership(externalId, name, did, bpn, state, processId, tenantId,
                participantProfileId, participantContextId, failureReason, version, externallyHosted);
    }

    public Membership provisioning(String tenantId, String participantProfileId) {
        return new Membership(externalId, name, did, bpn, MembershipState.PROVISIONING,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, failureReason,
                version, externallyHosted);
    }

    public Membership withParticipantContextId(String participantContextId) {
        return new Membership(externalId, name, did, bpn, state, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version, externallyHosted);
    }

    public Membership provisioned() {
        return withState(MembershipState.PROVISIONED);
    }

    /** Terminal success of an externally hosted member: the credential offer reached its wallet. */
    public Membership credentialsOffered() {
        return withState(MembershipState.CREDENTIALS_OFFERED);
    }

    public Membership rejected(String reason) {
        return new Membership(externalId, name, did, bpn, MembershipState.REJECTED,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, reason,
                version, externallyHosted);
    }

    public Membership failed(String reason) {
        return new Membership(externalId, name, did, bpn, MembershipState.FAILED,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, reason,
                version, externallyHosted);
    }

    /** The stored snapshot's lock token — set by the repositories on load and save. */
    public Membership withVersion(Long version) {
        return new Membership(externalId, name, did, bpn, state, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version, externallyHosted);
    }

    public boolean isTerminal() {
        return state == MembershipState.PROVISIONED
                || state == MembershipState.CREDENTIALS_OFFERED
                || state == MembershipState.REJECTED
                || state == MembershipState.FAILED;
    }
}
