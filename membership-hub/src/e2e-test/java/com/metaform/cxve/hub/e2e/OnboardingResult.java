package com.metaform.cxve.hub.e2e;

/**
 * The identities an onboarded participant is driven by for the rest of the suite: {@code holderId}
 * is the participant DID, {@code participantContextId} the EDC participant context. All of them
 * come straight from the Membership Hub's correlated membership record (see
 * {@link MembershipHubApi}), the member id included — the DECADE-X-ID the hub generates for a member
 * it hosts, or the BPN a Catena-X member declared.
 */
public record OnboardingResult(
        String externalId,
        String memberId,
        String holderId,
        String participantContextId
) {
}
