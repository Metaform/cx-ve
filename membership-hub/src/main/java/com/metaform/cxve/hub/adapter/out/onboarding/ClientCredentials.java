package com.metaform.cxve.hub.adapter.out.onboarding;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaform.cxve.hub.config.DataspaceProperties;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * OAuth2 client-credentials against an onboarding API's IdP (for the VE's own onboarding APIs:
 * its OSP IdP, Ory Hydra) — the hub authenticates exactly like any external onboarding service
 * provider, NOT via the platform's jwtlet (whose only grant exchanges Kubernetes workload tokens).
 *
 * <p>Deliberately un-cached: tokens are fetched per call. The hub makes a handful of onboarding
 * API calls per membership, not a stream.
 */
public class ClientCredentials {

    private final RestClient tokenClient;
    private final DataspaceProperties.Client client;

    public ClientCredentials(DataspaceProperties.Client client) {
        this.tokenClient = RestClient.builder().baseUrl(client.tokenUrl()).build();
        this.client = client;
    }

    public String getToken() {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        if (client.scope() != null && !client.scope().isBlank()) {
            formData.add("scope", client.scope());
        }

        var response = tokenClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // Hydra's default token_endpoint_auth_method for the seeded clients
                .headers(h -> h.setBasicAuth(client.clientId(), client.clientSecret() == null ? "" : client.clientSecret()))
                .body(formData)
                .retrieve()
                .body(TokenResponse.class);

        return Objects.requireNonNull(response, "empty token response").accessToken();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
