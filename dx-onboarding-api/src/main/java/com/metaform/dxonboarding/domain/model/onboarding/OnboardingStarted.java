package com.metaform.dxonboarding.domain.model.onboarding;

/**
 * Announced when an onboarding request is accepted — what lets an observer (the VE's compliance
 * tracker) follow the participant from here on: the process id and the DID it onboards under.
 *
 * @param processId  the request's id, for correlating its completion
 * @param externalId the applicant's reference (the Membership Hub's external id)
 * @param did        the participant's DID — its connector identity
 */
public record OnboardingStarted(String processId, String externalId, String did) {
}
