package com.metaform.cxve.domain.model;

import jakarta.validation.constraints.NotBlank;

/**
 * An OSP's callback configuration (the spec's
 * {@code OnboardingServiceProviderCallbackConfigurationRequest}): all four fields are Mandatory —
 * the callback target plus the OAuth2 client-credentials material this operator uses to
 * authenticate the outbound status calls against the OSP's own token endpoint. Enforced at the
 * web boundary via {@code @Valid}; stored entries from earlier revisions may still carry nulls
 * and are handled leniently on the outbound path.
 */
public record CallbackRequestData(
        @NotBlank String callbackUrl,
        @NotBlank String authUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret
) {
}
