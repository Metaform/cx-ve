package com.metaform.cxve.domain.model;

public record CallbackRequestData(
        String callbackUrl,
        String authUrl,
        String clientId,
        String clientSecret
) {
}
