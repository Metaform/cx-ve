package com.beardyinc.cxve.infrastructure.identityhub;

import com.beardyinc.cxve.auth.TokenProvider;
import com.beardyinc.cxve.infrastructure.identityhub.model.CredentialRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class IdentityHubClientImpl implements IdentityHubClient {
    private final TokenProvider tokenProvider;
    private final RestClient identityHubClient;

    public IdentityHubClientImpl(TokenProvider tokenProvider, @Qualifier("identityHubWebClient") RestClient identityHubClient) {
        this.tokenProvider = tokenProvider;
        this.identityHubClient = identityHubClient;
    }

    @Override
    public CredentialRequest getCredentialRequest(String participantContextId, String holderProcessID) {
        var token = getToken(participantContextId);
        return identityHubClient.get()
                .uri("/participants/{participantContextId}/credentials/request/{holderPid}",
                        participantContextId, holderProcessID)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toEntity(CredentialRequest.class)
                .getBody();
    }

    private String getToken(String participantContextId) {
        return tokenProvider.getToken(null, "admin");
    }
}
