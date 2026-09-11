package com.metaform.cxve.verification.domain.model;

/**
 * The permanent verification participant — the DSP-consumer side of every verification run. Its
 * identity is fixed configuration; this record carries what provisioning resolved for it.
 */
public record VerificationParticipant(
        String externalId,
        String name,
        String bpn,
        String did,
        String participantContextId) {

    public static VerificationParticipant from(Membership membership) {
        return new VerificationParticipant(
                membership.externalId(),
                membership.name(),
                membership.bpn(),
                membership.did(),
                membership.participantContextId());
    }
}
