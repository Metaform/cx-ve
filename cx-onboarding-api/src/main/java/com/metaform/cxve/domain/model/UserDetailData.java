package com.metaform.cxve.domain.model;

import jakarta.validation.constraints.NotBlank;

/**
 * An initial user of the registering company (CX-0009 §3 UserDetailData). {@code providerId},
 * {@code firstName}, {@code lastName} and {@code email} are Mandatory per spec;
 * {@code identityProviderId} is optional (absent → the OSP configuration's default IdP) as is
 * {@code username}.
 */
public record UserDetailData(
        String identityProviderId,
        @NotBlank String providerId,
        String username,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String email
) {
}
