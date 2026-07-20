package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.onboarding.CredentialIssuanceService;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placeholder credential issuance. In reality, the credential issuance is performed when provisioning the "wallet", i.e.
 * the participant, using the Tenant Manager API.
 * Therefor, this implementation simply asserts that the requested credentials have already been issued
 */
@Service
public class CredentialIssuanceServiceStub implements CredentialIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(CredentialIssuanceServiceStub.class);

    @Override
    public boolean issueBpnCredential(OnboardingProcess process) {
        log.debug("Issuing BPN credential for onboarding {} (bpn={})", process.id(), process.bpn());

        var holder = process.holderId();
        var holderProcess = process.holderProcessId();
        var participantContext = process.participantContextId();
        // get issuance process for holder

        return true;
    }

    @Override
    public boolean issueFrameworkAgreementCredential(OnboardingProcess process) {
        log.debug("Issuing Framework Agreement credential for onboarding {}", process.id());
        return true;
    }

    @Override
    public boolean issueMembershipCredential(OnboardingProcess process) {
        log.debug("Issuing Membership credential for onboarding {}", process.id());
        return true;
    }
}
