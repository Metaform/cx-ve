package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import com.beardyinc.cxve.onboarding.WalletService;
import org.springframework.stereotype.Service;

/**
 * Placeholder wallet provisioning. A real implementation would provision an operator-hosted wallet
 * or register a participant-owned one per CX-0149, based on the applicant's preference.
 */
@Service
public class WalletServiceStub implements WalletService {

    @Override
    public String provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
        return "wallet-" + process.id();
    }
}
