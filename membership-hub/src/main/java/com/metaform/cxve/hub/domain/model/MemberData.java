package com.metaform.cxve.hub.domain.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The caller-facing membership request — everything the Onboarding API's registration payload
 * needs except the {@code externalId} and (optionally) the DID, which this app supplies. The
 * annotated components mirror the fields the Onboarding API enforces, so a bad request fails here
 * rather than downstream.
 *
 * <p>The BPN is REQUIRED here even though the Onboarding API can assign one: its status callback
 * does not carry the assigned BPN back, and provisioning needs it (the {@code cfm.issuer} VPA
 * properties feed it to the certo activity). Requiring it keeps the hub's record authoritative.
 *
 * <p>The role and consent-status values are passed through verbatim (e.g. role
 * {@code ACTIVE_PARTICIPANT}, consent status {@code ACTIVE}); the Onboarding API validates them.
 */
public record MemberData(
        @NotBlank String name,
        @NotBlank String shortName,
        @NotBlank String bpn,
        String did,
        @NotEmpty List<UniqueId> uniqueIds,
        @NotEmpty List<String> companyRoles,
        @NotEmpty List<AgreementConsent> agreements
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
}
