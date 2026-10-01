package com.metaform.cxve.domain.model;

/**
 * Announced when an onboarding process reaches a terminal state: for {@link
 * OnboardingState#COMPLETED} the registration is confirmed — the participant is registered as a
 * credential holder with the IssuerService under the DID carried here. Its EDC resources (and with
 * them the actual credential issuance) are provisioned downstream, by whoever reacts to this
 * confirmation — this event deliberately carries no provisioning identifiers.
 *
 * <p>Where {@link OnboardingStarted} carries the identities the process is <em>expected</em> to run
 * under, this one carries what was actually recorded. The two agree in the normal case; a
 * subscriber that needs certainty should key off this event.
 *
 * @param processId      id of the onboarding process, matching the {@code OnboardingStarted} it closes
 * @param externalId     caller-supplied id, the key the status callbacks are keyed by
 * @param bpn            business partner number the participant was onboarded under
 * @param did            the participant's DID, as registered with the IssuerService
 * @param state
 * @param failureMessage
 */
public record OnboardingCompleted(String processId,
                                  String externalId,
                                  String bpn,
                                  String did,
                                  OnboardingState state,
                                  String failureMessage) {
}
