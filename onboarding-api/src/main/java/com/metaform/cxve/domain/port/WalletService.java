package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.ProvisionedParticipant;

/**
 * Ensures a CX-0149-compliant wallet exists to receive the onboarding credentials. In practice, this service hands the
 * onboarding over to CFM, which provisions not only the wallet, but also other components such as the control plane
 */
public interface WalletService {

    /**
     * Provisions the participant/wallet and returns its (possibly still-provisioning) state.
     */
    ProvisionedParticipant provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData);

    ProvisionedParticipant checkProvisionStatus(OnboardingProcess process);
}
