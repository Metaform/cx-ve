package com.metaform.cxve.adapter.out.issuer;

import com.metaform.cxve.adapter.out.auth.TokenProvider;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.HolderRegistrationService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Registers holders through the IssuerService Admin API, replacing what the CFM registration agent
 * used to do inside the provisioning orchestration: {@code POST
 * /v1beta/participants/{issuerContextId}/holders} with the participant's DID as both {@code did}
 * and {@code holderId}.
 *
 * <p>Auth mirrors that agent, too: the workload token is exchanged (resource {@code sudo}, scope
 * {@code issuer-admin-api:admin}). The admin scope is required — the exchanged token's {@code sub}
 * is no participant context the IssuerService knows, and non-admin scopes fail its caller
 * resolution with "No participant for 'sub = ...' found".
 */
@Service
public class IssuerServiceHolderRegistrationService implements HolderRegistrationService {

    private static final String SCOPE = "issuer-admin-api:admin";

    private static final Logger log = LoggerFactory.getLogger(IssuerServiceHolderRegistrationService.class);

    private final TokenProvider tokenProvider;
    private final RestClient restClient;
    private final String issuerContextId;
    private final String tokenResource;

    public IssuerServiceHolderRegistrationService(TokenProvider tokenProvider,
                                                  @Qualifier("issuerServiceClient") RestClient restClient,
                                                  @Value("${issuer-service.issuer-context-id:issuer}") String issuerContextId,
                                                  @Value("${issuer-service.token-resource:sudo}") String tokenResource) {
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
        this.issuerContextId = issuerContextId;
        this.tokenResource = tokenResource;
    }

    @Override
    public void registerHolder(OnboardingProcess process, PartnerRegistrationData registrationData) {
        var did = process.holderId();
        try {
            restClient.post()
                    .uri("/v1beta/participants/{issuerContextId}/holders", issuerContextId)
                    .header("Authorization", "Bearer " + tokenProvider.getToken(tokenResource, SCOPE))
                    .body(Map.of(
                            "did", did,
                            "holderId", did,
                            "name", registrationData.name()))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Registered holder '{}' with the IssuerService for onboarding {}", did, process.id());
        } catch (HttpClientErrorException.Conflict e) {
            // A holder left behind by an earlier, partially-failed drive of the same onboarding.
            // The entry exists, which is all this step is for.
            log.info("Holder '{}' already registered with the IssuerService, continuing onboarding {}", did, process.id());
        }
    }
}
