package com.metaform.cxve.domain.model;

/**
 * Read model over an onboarding process and its registration payload, returned by the repository's
 * active-registration queries: the state the onboarding has reached plus the effective registration
 * data. Only registrations that passed validation and were not rejected or failed surface as
 * active, so a match is either still in flight or a fully onboarded partner.
 */
public record PartnerRegistration(
        String processId,
        OnboardingState state,
        PartnerRegistrationData data
) {

    /**
     * Builds the view with the process's BPN and holder DID overlaid on the payload: the process
     * is authoritative for both — seeded from the submission and confirmed or overwritten by the
     * onboarding steps — so the payload's values are never consulted.
     */
    public static PartnerRegistration of(OnboardingProcess process, PartnerRegistrationData payload) {
        var effective = payload.withBpn(process.bpn()).withDid(process.holderId());
        return new PartnerRegistration(process.id(), process.state(), effective);
    }

    public boolean inFlight() {
        // REJECTED and FAILED attempts never surface as active registrations (so those partners
        // can re-register); anything active that isn't completed is in flight.
        return state != OnboardingState.COMPLETED;
    }
}
