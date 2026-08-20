package com.metaform.cxve.hub.adapter.out.onboarding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * OAuth2 client-credentials against the VE's OSP IdP (Ory Hydra) — the hub authenticates to the
 * Onboarding API exactly like any external onboarding service provider, NOT via the platform's
 * jwtlet (whose only grant exchanges Kubernetes workload tokens). The client must be seeded in
 * the IdP with the {@code configure_partner_registration} scope so it may register its callback.
 *
 * <p>Deliberately un-cached: tokens are fetched per call. The hub makes a handful of Onboarding
 * API calls per membership, not a stream.
 */
@Component
public class OspTokenProvider {

    private final RestClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private final String scope;

    public OspTokenProvider(@Qualifier("ospTokenClient") RestClient tokenClient,
                            @Value("${onboarding-api.auth.client-id:membership-hub}") String clientId,
                            @Value("${onboarding-api.auth.client-secret:}") String clientSecret,
                            @Value("${onboarding-api.auth.scope:configure_partner_registration}") String scope) {
        this.tokenClient = tokenClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    public String getToken() {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        formData.add("scope", scope);

        var response = tokenClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // Hydra's default token_endpoint_auth_method for the seeded clients
                .headers(h -> h.setBasicAuth(clientId, clientSecret))
                .body(formData)
                .retrieve()
                .body(TokenResponse.class);

        return Objects.requireNonNull(response, "empty token response").accessToken();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
