package com.beardyinc.cxve.infrastructure.proofing;

import com.beardyinc.cxve.domain.model.OnboardingProcess;
import com.beardyinc.cxve.domain.port.IdentityProofingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placeholder identity proofing that auto-approves. A real implementation would delegate to an
 * out-of-band proofing provider and only report verified once its callback confirms success.
 */
@Service
public class IdentityProofingServiceStub implements IdentityProofingService {

    private static final Logger log = LoggerFactory.getLogger(IdentityProofingServiceStub.class);

    @Override
    public String initiateProofing(OnboardingProcess process) {
        var reference = "proof-" + process.id();
        log.debug("Initiated identity proofing for onboarding {} (reference={})", process.id(), reference);
        return reference;
    }

    @Override
    public boolean isVerified(String proofingReference) {
        log.debug("Auto-approving identity proofing for reference={}", proofingReference);
        return true;
    }
}
