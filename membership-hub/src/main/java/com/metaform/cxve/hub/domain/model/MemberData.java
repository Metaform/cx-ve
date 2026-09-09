package com.metaform.cxve.hub.domain.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The caller-facing membership request — everything the Onboarding API's registration payload
 * needs (its spec-mandatory address and initial-user fields included) except the
 * {@code externalId} and (optionally) the DID, which this app supplies. The annotated components
 * mirror the fields the Onboarding API enforces, so a bad request fails here rather than
 * downstream.
 *
 * <p>The BPN is REQUIRED here even though the Onboarding API can assign one: provisioning needs
 * it up front (the {@code cfm.issuer} VPA properties feed it to the certo activity), and
 * requiring it keeps the hub's record authoritative.
 *
 * <p>{@code agreements} is HUB-INTERNAL since the registration payload lost its agreements
 * field: the ACTIVE agreement ids still drive the Tenant Manager deployment
 * ({@code deployParticipant}), they are just no longer forwarded to the Onboarding API.
 */
public record MemberData(
        @NotBlank String name,
        @NotBlank String shortName,
        @NotBlank String bpn,
        @NotBlank String city,
        @NotBlank String streetName,
        @NotBlank String countryAlpha2Code,
        @NotBlank String region,
        String did,
        @NotEmpty List<@Valid UniqueId> uniqueIds,
        @NotEmpty List<String> companyRoles,
        @NotEmpty List<@Valid AgreementConsent> agreements,
        @NotEmpty List<@Valid UserDetail> userDetails
) {

    public record UniqueId(@NotBlank String type, @NotBlank String value) {
    }

    public record AgreementConsent(@NotBlank String agreementId, @NotBlank String consentStatus) {

        // Not named like a bean getter on purpose: Jackson would otherwise serialize it as a
        // phantom "active" property into the stored payload JSON.
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
