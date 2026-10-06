package com.metaform.dxonboarding.adapter.out.issuer;

import com.metaform.dxonboarding.adapter.out.auth.TokenProvider;
import com.metaform.dxonboarding.config.IssuerServiceProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.port.CredentialOfferService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Has the IssuerService offer the holder the configured DECADE-X credential definitions
 * ({@code issuer-service.credential-definition-ids}), authorized like the holder registration. The
 * IssuerService resolves the holder's DID document and pushes a DCP CredentialOffer to the
 * CredentialService it advertises; an unknown definition id fails the whole offer.
 */
@Component
public class IssuerServiceCredentialOfferService implements CredentialOfferService {

    private static final Logger log = LoggerFactory.getLogger(IssuerServiceCredentialOfferService.class);

    private final RestClient restClient;
    private final TokenProvider tokenProvider;
    private final IssuerServiceProperties properties;

    public IssuerServiceCredentialOfferService(@Qualifier("issuerServiceClient") RestClient restClient,
                                               TokenProvider tokenProvider, IssuerServiceProperties properties) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    @Override
    public void offerCredentials(OnboardingRequest request) {
        var holderId = request.connectorId();
        restClient.post()
                .uri("/v1/participants/{issuerContextId}/credentials/offer", properties.issuerContextId())
                .header("Authorization", "Bearer " + tokenProvider.getToken(properties.tokenResource(),
                        IssuerServiceHolderRegistrationService.SCOPE))
                .body(Map.of("holderId", holderId, "credentials", properties.credentialDefinitionIds()))
                .retrieve()
                .toBodilessEntity();
        log.info("offered {} to holder {}", properties.credentialDefinitionIds(), holderId);
    }
}
