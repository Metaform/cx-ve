package com.metaform.dxonboarding.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Where a submitter wants its decisions delivered. With {@code tokenUrl} set, every delivery
 * carries a bearer obtained there via client_credentials with {@code clientId}/{@code clientSecret}.
 * The secret is write-only: it is never returned.
 */
public record Webhook(
        @NotBlank String url,
        String tokenUrl,
        String clientId,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String clientSecret
) {
}
