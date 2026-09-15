package com.metaform.cxve.hub.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
 * ({@code deployParticipant}), they are just no longer forwarded to the Onboarding API. An
 * externally hosted member has no deployment, so nothing consumes them — they stay required so
 * the request shape is the same either way.
 *
 * <p>{@code externallyHosted} marks a member whose participant resources (connector, wallet, DID
 * document) already exist OUTSIDE this environment — a third-party system under test. The
 * membership then carries the {@code did} those resources answer under (mandatory: the template
 * fallback would mint a DID under this environment's authority, which such a member does not
 * control), and the hub provisions nothing for it, offering credentials instead. Absent means
 * {@code false}: a member this environment provisions.
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
        @NotEmpty List<@Valid UserDetail> userDetails,
        Boolean externallyHosted
) {

    /**
     * The tri-state wire field collapsed for consumers: absent reads as internally provisioned.
     * Deliberately NOT named {@code isExternallyHosted} — that would be a bean getter for the same
     * property as the record component, and Jackson merges the two, so an annotation on either
     * (a {@code @JsonIgnore}, say) would silently drop the wire field itself.
     */
    @JsonIgnore
    public boolean hostedExternally() {
        return Boolean.TRUE.equals(externallyHosted);
    }

    @JsonIgnore
    @AssertTrue(message = "did is required when externallyHosted is true")
    public boolean isCarryingTheDidOfAnExternallyHostedMember() {
        return !hostedExternally() || (did != null && !did.isBlank());
    }

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
