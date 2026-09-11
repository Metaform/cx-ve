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

    public Membership provisioning(String tenantId, String participantProfileId) {
        return new Membership(externalId, name, did, bpn, MembershipState.PROVISIONING,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, failureReason, version);
    }

    public Membership withParticipantContextId(String participantContextId) {
        return new Membership(externalId, name, did, bpn, state, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version);
    }

    public Membership provisioned() {
        return withState(MembershipState.PROVISIONED);
    }

    public Membership rejected(String reason) {
        return new Membership(externalId, name, did, bpn, MembershipState.REJECTED,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, reason, version);
    }

    public Membership failed(String reason) {
        return new Membership(externalId, name, did, bpn, MembershipState.FAILED,
                onboardingProcessId, tenantId, participantProfileId, participantContextId, reason, version);
    }

    /** The stored snapshot's lock token — set by the repositories on load and save. */
    public Membership withVersion(Long version) {
        return new Membership(externalId, name, did, bpn, state, onboardingProcessId, tenantId,
                participantProfileId, participantContextId, failureReason, version);
    }

    public boolean isTerminal() {
        return state == MembershipState.PROVISIONED
                || state == MembershipState.REJECTED
                || state == MembershipState.FAILED;
    }
}
