package com.metaform.cxve.hub.e2e;

public record SetCallbackRequest(
        String callbackUrl,
        String authUrl,
        String clientId,
        String clientSecret
) {
}