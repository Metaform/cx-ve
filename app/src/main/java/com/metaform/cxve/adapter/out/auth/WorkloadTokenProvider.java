/*
 *  Copyright (c) 2026 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package com.metaform.cxve.adapter.out.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import static java.util.Optional.ofNullable;

/**
 * This provider reads a workload token from a file share, provided by Kubernetes, and exchanges it for a scoped token
 * using the Token Exchange Protocol (RFC 8693 - OAuth 2.0 Token Exchange).
 */
@Component("token-exchange")
public class WorkloadTokenProvider implements TokenProvider {
    private final String tokenFilePath;
    private final String tokenExchangeAudience;
    private final String defaultResource;
    private final RestClient restClient;

    public WorkloadTokenProvider(@Value("${token.file.path:/var/run/secrets/jwtlet/token}") String tokenFilePath,
                                 @Value("${token.exchange.audience:edcv}") String tokenExchangeAudience,
                                 @Value("${token.exchange.resource:redline}") String defaultResource,
                                 @Qualifier("tokenExchangeClient") RestClient restClient) {
        this.tokenFilePath = tokenFilePath;
        this.tokenExchangeAudience = tokenExchangeAudience;
        this.defaultResource = defaultResource;
        this.restClient = restClient;
    }

    @Override
    public String getToken(String resource, String scopes) {
        try {
            var tokenContent = Files.readString(Path.of(tokenFilePath));

            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
            formData.add("subject_token", tokenContent);
            formData.add("subject_token_type", "urn:ietf:params:oauth:token-type:jwt");
            formData.add("audience", tokenExchangeAudience);
            formData.add("resource", ofNullable(resource).orElse(defaultResource));
            formData.add("scope", scopes);

            var response = restClient.post()
                    .uri("/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(TokenResponse.class);

            return Objects.requireNonNull(response).accessToken();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
