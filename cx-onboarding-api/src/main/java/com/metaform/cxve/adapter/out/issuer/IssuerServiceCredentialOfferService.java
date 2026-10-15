package com.metaform.cxve.adapter.out.issuer;

import com.metaform.cxve.adapter.out.auth.TokenProvider;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.port.CredentialOfferService;
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
 * definitions to offer. The IssuerService resolves the holder's DID document, takes the
 * {@code CredentialService} service endpoint from it and POSTs a DCP CredentialOffer there.
 *
 * <p>The holder id is the participant's DID, which is also what
 * {@link IssuerServiceHolderRegistrationService} registered the holder under — the two steps
 * address the same entry, in order.
 *
 * <p>The offered definition ids are configured rather than derived: they must name credential
 * definitions seeded with the issuer, and the defaults match the ids the Catena-X profile chart
 * seeds. An id that names no definition makes the IssuerService reject the whole offer.
 *
 * <p>Auth is the holder registration's, on the same client: the workload token is exchanged under
 * a mapping ({@code token-resource}) whose scopes include {@code issuer-admin-api:admin} — the
 * admin scope is what passes the IssuerService's caller resolution.
 *
 * <p>NOTE the offer is not idempotent the way the holder registration is. Sending it twice has the
 * participant's wallet request a second copy of every credential, and a wallet holding two
 * MembershipCredentials fails presentations. The orchestration's state machine is what keeps it to
 * once per process: the step runs on the transition out of {@code WALLET_PROVISIONED}, and a
 * re-driven process resumes after it.
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
    public void offerCredentials(OnboardingProcess process) {
        var holderId = process.holderId();
        restClient.post()
                .uri("/v1/participants/{issuerContextId}/credentials/offer", issuerContextId)
                .header("Authorization", "Bearer " + tokenProvider.getToken(tokenResource, SCOPE))
                .body(Map.of("holderId", holderId, "credentials", credentialDefinitionIds))
                .retrieve()
                .toBodilessEntity();
        log.info("Offered {} to holder '{}' for onboarding {}", credentialDefinitionIds, holderId, process.id());
    }
}
