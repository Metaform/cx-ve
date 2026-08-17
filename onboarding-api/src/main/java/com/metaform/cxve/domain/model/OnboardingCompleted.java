package com.metaform.cxve.domain.model;

/**
 * Announced when an onboarding process reaches {@link OnboardingState#COMPLETED}: the participant is
 * provisioned, its wallet exists and its credentials have been issued.
 *
 * <p>Where {@link OnboardingStarted} carries the identities the process is <em>expected</em> to run
 * under, this one carries what was actually provisioned — the DID here is the holder id the platform
 * assigned, not a value derived from a template. The two agree in the normal case; a subscriber that
 * needs certainty should key off this event.
 *
 * @param processId            id of the onboarding process, matching the {@code OnboardingStarted} it closes
 * @param externalId           caller-supplied id, the key the status callbacks are keyed by
 * @param bpn                  business partner number the participant was onboarded under
 * @param did                  the participant's DID, as provisioned
 * @param participantContextId the participant context the platform created; the handle its APIs are addressed by
 * @param state
 * @param failureMessage
 */
public record OnboardingCompleted(String processId,
                                  String externalId,
                                  String bpn,
                                  String did,
                                  String participantContextId,
                                  OnboardingState state,
                                  String failureMessage) {
}
