package com.metaform.cxve.hub.adapter.out.onboarding.catenax;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The Catena-X shape of a member request's {@code registration} object — everything the CX
 * onboarding API's registration payload needs (its spec-mandatory address and initial-user fields
 * included) beyond the common member fields. The annotated components mirror the fields the
 * onboarding API enforces, so a bad request fails in the hub rather than downstream.
 *
 * <p>{@code agreements} is HUB-INTERNAL since the registration payload lost its agreements field:
 * the ACTIVE agreement ids are the member's {@code memberOf} in its participant profile, they are
 * just no longer forwarded to the onboarding API. A member that brings its own DID has no
 * deployment, so nothing consumes them — they stay required so the request shape is the same
 * either way.
 */
public record CatenaXRegistration(
        @NotBlank String city,
        @NotBlank String streetName,
        @NotBlank String countryAlpha2Code,
        @NotBlank String region,
        @NotEmpty List<@Valid UniqueId> uniqueIds,
        @NotEmpty List<String> companyRoles,
        @NotEmpty List<@Valid AgreementConsent> agreements,
        @NotEmpty List<@Valid UserDetail> userDetails
) {

    public record UniqueId(@NotBlank String type, @NotBlank String value) {
    }

    public record AgreementConsent(@NotBlank String agreementId, @NotBlank String consentStatus) {

        // Not named like a bean getter on purpose: Jackson would otherwise serialize it as a
        // phantom "active" property.
        public boolean hasActiveConsent() {
            return "ACTIVE".equalsIgnoreCase(consentStatus);
        }
    }

    public record UserDetail(
            String identityProviderId,
            @NotBlank String providerId,
            String username,
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotBlank String email
    ) {
    }
}
