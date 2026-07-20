package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.onboarding.CredentialIssuanceService;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import org.springframework.stereotype.Service;

/**
 * Placeholder credential issuance. A real implementation would issue verifiable credentials into the
 * participant's wallet via the issuer service. Here the calls are no-ops so the flow can complete.
 */
@Service
public class CredentialIssuanceServiceStub implements CredentialIssuanceService {

    @Override
    public void issueBpnCredential(OnboardingProcess process) {
        // no-op stub
    }

    @Override
    public void issueFrameworkAgreementCredential(OnboardingProcess process) {
        // no-op stub
    }

    @Override
    public void issueMembershipCredential(OnboardingProcess process) {
        // no-op stub
    }
}
