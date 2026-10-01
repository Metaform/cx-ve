package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistrationData;

/**
 * Registers the participant as a credential holder with the dataspace's IssuerService, under the
 * DID recorded on the process. This is what "confirms" a registration: once the holder entry
 * exists, the issuer can issue the onboarding credentials to the participant — the EDC resources
 * that will request and hold them are provisioned separately, downstream of this process.
 */
public interface HolderRegistrationService {

    /**
     * Creates the holder entry, idempotently: registering an already-registered holder is a no-op.
     * Throws on any other failure.
     */
    void registerHolder(OnboardingProcess process, PartnerRegistrationData registrationData);
}
