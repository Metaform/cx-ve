package com.beardyinc.cxve.model;

public record OnboardingServiceProviderCallbackRequestData(
        String callbackUrl,
        String authUrl,
        String clientId,
        String clientSecret
) {
}
