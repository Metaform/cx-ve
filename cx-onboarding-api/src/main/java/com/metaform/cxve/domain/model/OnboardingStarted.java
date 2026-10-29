package com.metaform.cxve.domain.model;

/**
 * Announced when an onboarding process is accepted and begins running.
 *
 * <p>Both identities are already determined at this point, which is what makes the event useful to
 * a subscriber: the BPN is the one the process will run under (the registration payload carries it
 * and {@code BusinessPartnerNumberService.resolveOrCreate} keeps a supplied BPN verbatim), and the
 * DID is derived from the same rule the participant will later be provisioned with. A consumer can
 * therefore correlate on either without waiting for the process to complete.
 *
 * @param processId  id of the onboarding process, for correlating later state
 * @param externalId caller-supplied id, the key the status callbacks are keyed by
 * @param bpn        business partner number the participant onboards under
 * @param did        the participant's DID
 */
public record OnboardingStarted(String processId, String externalId, String bpn, String did) {
}
