package com.metaform.cxve.e2e;

public record SetCallbackRequest(
        String callbackUrl,
        String authUrl,
        String clientId,
        String clientSecret
) {
}