package com.metaform.cxve.domain.model;

/**
 * The readable projection of a stored {@link CallbackRequestData} (the spec's
 * {@code OnboardingServiceProviderCallbackConfigurationResponse}): everything EXCEPT the client
 * secret, which is write-only — the GET must never return it, under any spelling.
 */
public record CallbackResponseData(
        String callbackUrl,
        String authUrl,
        String clientId
) {

    public static CallbackResponseData from(CallbackRequestData data) {
        return data == null ? null : new CallbackResponseData(data.callbackUrl(), data.authUrl(), data.clientId());
    }
}
