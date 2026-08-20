package com.metaform.cxve.e2e;

/**
 * The identities an onboarded participant is driven by for the rest of the suite: {@code holderId}
 * is the participant DID, {@code participantContextId} the EDC participant context. All of them
 * come straight from the Membership Hub's correlated membership record (see
 * {@link MembershipHubApi}); the BPN is the one the suite submitted, restated deterministically.
 */
public record OnboardingResult(
        String externalId,
        String bpn,
        String holderId,
        String participantContextId
) {
}
