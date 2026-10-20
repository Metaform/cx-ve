package com.metaform.dxonboarding.adapter.out.issuer;

import com.metaform.dxonboarding.adapter.out.auth.TokenProvider;
import com.metaform.dxonboarding.config.IssuerServiceProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.port.CredentialOfferService;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Has the IssuerService offer the holder its DECADE-X credentials, authorized like the holder
 * registration: the Legal Entity VC, and the Site VC when the participant declared a business site
 * ({@code issuer-service.credential-definitions}). The IssuerService resolves the holder's DID
 * document and pushes a DCP CredentialOffer to the CredentialService it advertises; an unknown
 * definition id fails the whole offer.
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
        var definitions = properties.credentialDefinitions();
        var offered = new ArrayList<String>();
        offered.add(definitions.legalEntity());
        // without a site the holder has no claims for the Site VC, and its generation would fail
        // the whole issuance
        DecadeXCredentialClaims.firstSite(request).ifPresent(site -> offered.add(definitions.site()));
        restClient.post()
                .uri("/v1/participants/{issuerContextId}/credentials/offer", properties.issuerContextId())
                .header("Authorization", "Bearer " + tokenProvider.getToken(properties.tokenResource(),
                        IssuerServiceHolderRegistrationService.SCOPE))
                .body(Map.of("holderId", holderId, "credentials", offered))
                .retrieve()
                .toBodilessEntity();
        log.info("offered {} to holder {}", offered, holderId);
    }
}
