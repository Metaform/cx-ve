package com.metaform.dxonboarding.adapter.out.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaform.dxonboarding.domain.model.Decision;
import com.metaform.dxonboarding.domain.model.Webhook;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Delivers a decision to a webhook, with a client-credentials bearer when the webhook asks for one. */
@Component
public class WebhookSender {

    private final RestClient restClient = RestClient.create();

    public void send(Webhook webhook, Decision decision) {
        var request = restClient.post().uri(webhook.url()).contentType(MediaType.APPLICATION_JSON);
        if (webhook.tokenUrl() != null && !webhook.tokenUrl().isBlank()) {
            request = request.header("Authorization", "Bearer " + token(webhook));
        }
        request.body(decision).retrieve().toBodilessEntity();
    }

    private String token(Webhook webhook) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        var response = restClient.post()
                .uri(webhook.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(h -> h.setBasicAuth(webhook.clientId(), webhook.clientSecret() == null ? "" : webhook.clientSecret()))
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        return Objects.requireNonNull(response, "empty token response").accessToken();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
