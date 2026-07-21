package com.beardyinc.cxve.infrastructure.identityhub;

import com.beardyinc.cxve.domain.model.OnboardingProcess;
import com.beardyinc.cxve.domain.port.CredentialIssuanceService;
import com.beardyinc.cxve.infrastructure.identityhub.IdentityHubClient;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Placeholder credential issuance. In reality, the credential issuance is performed when provisioning the "wallet", i.e.
 * the participant, using the Tenant Manager API.
 * Therefor, this implementation simply asserts that the requested credentials have already been issued
 */
@Service
public class CredentialIssuanceServiceStub implements CredentialIssuanceService {

    private static final Logger log = LoggerFactory.getLogger(CredentialIssuanceServiceStub.class);
    private final IdentityHubClient identityHubClient;

    public CredentialIssuanceServiceStub(IdentityHubClient identityHubClient) {
        this.identityHubClient = identityHubClient;
    }

    @Override
    public boolean issueBpnCredential(OnboardingProcess process) {
        log.debug("Issuing BPN credential for onboarding {} (bpn={})", process.id(), process.bpn());
        return checkCredential(process, "BpnCredential");
    }


    @Override
    public boolean issueFrameworkAgreementCredential(OnboardingProcess process) {
        log.debug("Issuing Framework Agreement credential for onboarding {}", process.id());
        return checkCredential(process, "FrameworkAgreementCredential");
    }

    @Override
    public boolean issueMembershipCredential(OnboardingProcess process) {
        log.debug("Issuing Membership credential for onboarding {}", process.id());
        return checkCredential(process, "MembershipCredential");
    }

    private boolean checkCredential(OnboardingProcess process, String credentialType) {
        var holderProcess = process.holderProcessId();
        var participantContext = process.participantContextId();
        // get issuance process for holder
        try {
            var credential = identityHubClient.getCredentialRequest(participantContext, holderProcess);

            if(credential.typesAndFormats().stream().noneMatch(dc -> dc.credentialType().equals(credentialType))){
                log.warn("Expected to find a credential of type [{}]", credentialType);
                return false;
            }
            if (!Objects.equals(credential.status(), "ISSUED")) {
                log.warn("Credential status of [{}] expected to be ISSUED but was '{}'", credentialType, credential.status());
                return false;
            }
            return true;
        } catch (HttpClientErrorException ex) {
            log.warn("Error checking credential of type '%s': %s".formatted(credentialType, ex.getMessage()));
            return false;
        }
    }
}
