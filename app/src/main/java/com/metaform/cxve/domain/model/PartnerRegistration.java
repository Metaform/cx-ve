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
     * Builds the view with the effective registration data: the BPN and DID assigned on the
     * process during onboarding take precedence over the submitted payload, which may lack both.
     */
    public static PartnerRegistration of(OnboardingProcess process, PartnerRegistrationData payload) {
        var effective = payload
                .withBpn(process.bpn() != null ? process.bpn() : payload.bpn())
                .withDid(process.holderId() != null ? process.holderId() : payload.did());
        return new PartnerRegistration(process.id(), process.state(), effective);
    }

    public boolean inFlight() {
        // REJECTED and FAILED attempts never surface as active registrations (so those partners
        // can re-register); anything active that isn't completed is in flight.
        return state != OnboardingState.COMPLETED;
    }
}
