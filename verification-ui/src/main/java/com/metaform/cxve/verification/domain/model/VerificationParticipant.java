package com.metaform.cxve.verification.domain.model;

/**
 * A permanent verification participant — the DSP-consumer side of every verification run in its
 * dataspace (there is one per dataspace). Its identity is fixed configuration; this record carries
 * what provisioning resolved for it.
 */
public record VerificationParticipant(
        String externalId,
        String dataspace,
        String name,
        String memberId,
        String did,
        String participantContextId) {

    public static VerificationParticipant from(Membership membership) {
        return new VerificationParticipant(
                membership.externalId(),
                membership.dataspace(),
                membership.name(),
                membership.memberId(),
                membership.did(),
                membership.participantContextId());
    }
}
