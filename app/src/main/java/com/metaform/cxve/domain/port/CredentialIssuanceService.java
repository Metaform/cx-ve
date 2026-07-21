package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.OnboardingProcess;

/**
 * Issues the three CX-0006 verifiable credentials into the participant's wallet. Issuance of the
 * Membership credential is the step that formally completes onboarding, so it is issued last.
 */
public interface CredentialIssuanceService {

    boolean issueBpnCredential(OnboardingProcess process);

    boolean issueFrameworkAgreementCredential(OnboardingProcess process);

    /**
     * Completes onboarding. Issue only after the BPN and Framework Agreement credentials.
     */
    boolean issueMembershipCredential(OnboardingProcess process);
}
