package com.metaform.cxve.domain.model;

public record OnboardingServiceProviderCallbackRequestData(
        String callbackUrl,
        String authUrl,
        String clientId,
        String clientSecret
) {
}
