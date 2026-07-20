package com.beardyinc.cxve.onboarding;

/**
 * Issues the three CX-0006 verifiable credentials into the participant's wallet. Issuance of the
 * Membership credential is the step that formally completes onboarding, so it is issued last.
 */
public interface CredentialIssuanceService {

    void issueBpnCredential(OnboardingProcess process);

    void issueFrameworkAgreementCredential(OnboardingProcess process);

    /** Completes onboarding. Issue only after the BPN and Framework Agreement credentials. */
    void issueMembershipCredential(OnboardingProcess process);
}
