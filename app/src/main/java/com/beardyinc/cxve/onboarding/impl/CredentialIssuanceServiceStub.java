package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.onboarding.CredentialIssuanceService;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placeholder credential issuance. A real implementation would issue verifiable credentials into the
 * participant's wallet via the issuer service. Here the calls are no-ops so the flow can complete.
 */
@Service
public class CredentialIssuanceServiceStub implements CredentialIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(CredentialIssuanceServiceStub.class);

    @Override
    public void issueBpnCredential(OnboardingProcess process) {
        log.debug("Issuing BPN credential for onboarding {} (bpn={})", process.id(), process.bpn());
    }

    @Override
    public void issueFrameworkAgreementCredential(OnboardingProcess process) {
        log.debug("Issuing Framework Agreement credential for onboarding {}", process.id());
    }

    @Override
    public void issueMembershipCredential(OnboardingProcess process) {
        log.debug("Issuing Membership credential for onboarding {}", process.id());
    }
}
