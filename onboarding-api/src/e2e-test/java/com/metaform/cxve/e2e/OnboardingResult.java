package com.metaform.cxve.e2e;

/**
 * The identities an onboarded participant is driven by for the rest of the suite: {@code holderId}
 * is the participant DID, {@code participantContextId} the EDC participant context. The status
 * callback no longer carries them (its payload is the slim OSP contract, see
 * {@link RegistrationStatusCallback}), so the suite recovers them the way any VE-side actor would:
 * BPN and DID by the same deterministic rules the registration was submitted under, the
 * participant context id from the CFM tenant manager (see {@link TenantManagerApi}).
 */
public record OnboardingResult(
        String externalId,
        String bpn,
        String holderId,
        String participantContextId
) {
}
