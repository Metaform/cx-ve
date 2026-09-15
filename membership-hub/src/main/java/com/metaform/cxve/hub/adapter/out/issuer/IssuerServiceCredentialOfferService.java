package com.metaform.cxve.hub.adapter.out.issuer;

import com.metaform.cxve.hub.adapter.out.auth.TokenProvider;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.port.CredentialOfferService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Sends credential offers through the IssuerService Admin API: {@code POST
 * /v1/participants/{issuerContextId}/credentials/offer} with the holder id and the credential
 * definitions to offer. The IssuerService then resolves the holder's DID document, takes the
 * {@code CredentialService} service endpoint from it and POSTs a DCP CredentialOffer there — so
 * the member's DID must be resolvable, and its Credential Service reachable, FROM THE
 * ISSUERSERVICE POD at the moment this runs.
 *
 * <p>The holder id is the member's DID: the Onboarding API registers holders with
 * {@code did == holderId} (it has no id of its own to mint), so the hub can address a holder it
 * never created itself.
 *
 * <p>The offered definition ids are configured rather than derived — they must name credential
 * definitions seeded with the issuer, and the defaults match the ids the Catena-X profile chart
 * seeds. An id that names no definition makes the IssuerService reject the whole offer.
 *
 * <p>Auth mirrors the Onboarding API's holder registration, which addresses the same API: the
 * workload token is exchanged under a mapping ({@code token-resource}) whose scopes include
 * {@code issuer-admin-api:admin}. The admin scope is required rather than the narrower
 * {@code credentials:write} that the gateway route asks for — the exchanged token's {@code sub}
 * is no participant context the IssuerService knows, and a non-admin caller fails its resolution
 * with "No participant for 'sub = ...' found"; at the gateway, admin satisfies the route anyway
 * (clearglass expands admin ⊇ write ⊇ read).
 */
@Service
public class IssuerServiceCredentialOfferService implements CredentialOfferService {

    private static final String SCOPE = "issuer-admin-api:admin";

    private static final Logger log = LoggerFactory.getLogger(IssuerServiceCredentialOfferService.class);

    private final TokenProvider tokenProvider;
    private final RestClient restClient;
    private final String issuerContextId;
    private final String tokenResource;
    private final List<String> credentialDefinitionIds;

    public IssuerServiceCredentialOfferService(TokenProvider tokenProvider,
                                               @Qualifier("issuerServiceClient") RestClient restClient,
                                               @Value("${issuer-service.issuer-context-id:issuer}") String issuerContextId,
                                               @Value("${issuer-service.token-resource:sudo}") String tokenResource,
                                               @Value("${issuer-service.credential-definition-ids:membership-credential-def,bpn-credential-def,gov-credential-def}") List<String> credentialDefinitionIds) {
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
        this.issuerContextId = issuerContextId;
        this.tokenResource = tokenResource;
        this.credentialDefinitionIds = credentialDefinitionIds;
    }

    @Override
    public void sendOffer(Membership membership) {
        var did = membership.did();
        restClient.post()
                .uri("/v1/participants/{issuerContextId}/credentials/offer", issuerContextId)
                .header("Authorization", "Bearer " + tokenProvider.getToken(tokenResource, SCOPE))
                .body(Map.of(
                        "holderId", did,
                        "credentials", credentialDefinitionIds))
                .retrieve()
                .toBodilessEntity();
        log.info("Offered credentials {} to holder '{}' for membership '{}'",
                credentialDefinitionIds, did, membership.externalId());
    }
}
