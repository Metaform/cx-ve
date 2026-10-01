package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The Decade-X shape of a member request's {@code registration} object: what a Decade-X
 * membership application needs beyond the common member fields (whose {@code name} is the legal
 * name and {@code memberId} the member number).
 */
public record DecadeXRegistration(
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
        @NotBlank @Email String contactEmail
) {
}
