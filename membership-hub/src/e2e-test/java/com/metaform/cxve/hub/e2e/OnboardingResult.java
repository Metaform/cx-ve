package com.metaform.cxve.hub.e2e;

/**
 * The identities an onboarded participant is driven by for the rest of the suite: {@code holderId}
 * is the participant DID, {@code participantContextId} the EDC participant context. All of them
 * come straight from the Membership Hub's correlated membership record (see
 * {@link MembershipHubApi}), the DECADE-X-ID ({@code memberId}) included — the hub generates it
 * for a member it hosts.
 */
public record OnboardingResult(
        String externalId,
        String memberId,
        String holderId,
        String participantContextId
) {
}
