package com.beardyinc.cxve.onboarding;

import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.model.PartnerRegistrationData;

/**
 * Ensures a CX-0149-compliant wallet exists to receive the onboarding credentials. Depending on the
 * applicant's preference this either provisions an operator-hosted wallet or registers a
 * participant-owned one.
 */
public interface WalletService {

    /**
     * @return the wallet identifier credentials will be issued into.
     */
    ParticipantProfile provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData);

    ParticipantProfile checkProvisionStatus(OnboardingProcess process);
}
