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
 * <p>A member that brought its own DID is not provisioned here: its participant resources live
 * elsewhere, so {@code tenantId}/{@code participantProfileId}/{@code participantContextId} stay
 * null for the record's whole life — such a record starts at SUBMITTED. Either way the membership
 * ends at CREDENTIALS_OFFERED — every member is offered its credentials over DCP.
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
        @JsonIgnore Long version
) {

    /** A member whose resources this environment deploys: provisioning first, registration after. */
    public static Membership provisioning(String externalId, String name, String did, String bpn) {
        return new Membership(externalId, name, did, bpn, MembershipState.PROVISIONING,
                null, null, null, null, null, null);
    }

    /** A member that brought its own resources: nothing to deploy, the registration goes out now. */
    public static Membership submitted(String externalId, String name, String did, String bpn) {
        return new Membership(externalId, name, did, bpn, MembershipState.SUBMITTED,
                null, null, null, null, null, null);
    }

    public Membership withState(MembershipState newState) {
        return new Membership(externalId, name, did, bpn, newState, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version);
    }

    public Membership withOnboardingProcessId(String processId) {
        return new Membership(externalId, name, did, bpn, state, processId, tenantId,
                participantProfileId, participantContextId, failureReason, version);
    }

    /** Records what the Tenant Manager assigned when the deployment was accepted. */
    public Membership withProfile(String tenantId, String participantProfileId) {
        return new Membership(externalId, name, did, bpn, state, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version);
    }

    public Membership withParticipantContextId(String participantContextId) {
        return new Membership(externalId, name, did, bpn, state, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version);
    }

    public Membership provisioned() {
        return withState(MembershipState.PROVISIONED);
    }

    /** Terminal success of any member: its registration was confirmed, offer included. */
    public Membership credentialsOffered() {
        return withState(MembershipState.CREDENTIALS_OFFERED);
    }

    public Membership rejected(String reason) {
        return new Membership(externalId, name, did, bpn, MembershipState.REJECTED,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, reason,
                version);
    }

    public Membership failed(String reason) {
        return new Membership(externalId, name, did, bpn, MembershipState.FAILED,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, reason,
                version);
    }

    /** The stored snapshot's lock token — set by the repositories on load and save. */
    public Membership withVersion(Long version) {
        return new Membership(externalId, name, did, bpn, state, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version);
    }

    /**
     * Whether this record still OCCUPIES its DID and BPN. A rejected or failed attempt does not —
     * neither does a row stranded in the legacy REGISTERING state, which nothing drives any more —
     * so onboarding the same member again is allowed after one of those.
     */
    @JsonIgnore
    public boolean isLive() {
        return state != MembershipState.REJECTED
                && state != MembershipState.FAILED
                && state != MembershipState.REGISTERING;
    }

    public boolean isTerminal() {
        // PROVISIONED is NOT terminal: a provisioned member is still owed its registration (and
        // with it the credential offer), so a caller polling this record keeps reading.
        return state == MembershipState.CREDENTIALS_OFFERED
                || state == MembershipState.REJECTED
                || state == MembershipState.FAILED;
    }
}
