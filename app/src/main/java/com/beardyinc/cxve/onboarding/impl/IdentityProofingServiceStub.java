package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.onboarding.IdentityProofingService;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import org.springframework.stereotype.Service;

/**
 * Placeholder identity proofing that auto-approves. A real implementation would delegate to an
 * out-of-band proofing provider and only report verified once its callback confirms success.
 */
@Service
public class IdentityProofingServiceStub implements IdentityProofingService {

    @Override
    public String initiateProofing(OnboardingProcess process) {
        return "proof-" + process.id();
    }

    @Override
    public boolean isVerified(String proofingReference) {
        return true;
    }
}
