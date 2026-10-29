package com.metaform.dxonboarding.adapter.out.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaform.dxonboarding.config.TokenProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Exchanges the pod's Kubernetes workload token for a scoped token at the jwtlet (RFC 8693 Token
 * Exchange), as the Catena-X onboarding API does. The token file is re-read on every call: the
 * projected token rotates, and the jwtlet mapping is seeded after the pod starts, so nothing about
 * it is resolved at startup.
 */
@Component
public class WorkloadTokenProvider implements TokenProvider {

    private final TokenProperties properties;
    private final RestClient restClient;

    public WorkloadTokenProvider(TokenProperties properties, @Qualifier("tokenExchangeClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public String getToken(String resource, String scopes) {
        String subjectToken;
        try {
            subjectToken = Files.readString(Path.of(properties.file().path()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the workload token at " + properties.file().path(), e);
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.add("subject_token", subjectToken);
        form.add("subject_token_type", "urn:ietf:params:oauth:token-type:jwt");
        form.add("audience", properties.exchange().audience());
        form.add("resource", Objects.requireNonNull(resource, "resource (the jwtlet mapping to exchange under) must be given"));
        form.add("scope", scopes);
        var response = restClient.post()
                .uri("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        return Objects.requireNonNull(response, "empty token exchange response").accessToken();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
