package com.metaform.cxve.domain.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * DTO for incoming partner registration requests (the payload of
 * POST /api/v2/administration/registration/Network/partnerRegistration). The annotated components
 * — name, bpn, shortName, uniqueIds, externalId, companyRoles and agreements — are mandatory and
 * must be supplied by callers; they are enforced at the web boundary via {@code @Valid} on the
 * controller, so a request missing any of them is rejected with 400 before it reaches the
 * onboarding flow. All other components are optional.
 */
public record PartnerRegistrationData(
        @NotBlank String name,
        String city,
        String streetName,
        String countryAlpha2Code,
        @NotBlank String bpn,
        @NotBlank String shortName,
        String region,
        String streetAdditional,
        String streetNumber,
        String zipCode,
        @NotEmpty List<CompanyUniqueIdData> uniqueIds,
        @NotBlank String externalId,
        List<UserDetailData> userDetails,
        @NotEmpty List<CompanyRoleId> companyRoles,
        String did,
        @NotEmpty List<AgreementConsentData> agreements,
        List<DocumentUpload> documents,
        Boolean autoSubmit
) {

    public PartnerRegistrationData withBpn(String newBpn) {
        return new PartnerRegistrationData(name, city, streetName, countryAlpha2Code, newBpn, shortName,
                region, streetAdditional, streetNumber, zipCode, uniqueIds, externalId, userDetails,
                companyRoles, did, agreements, documents, autoSubmit);
    }

    public PartnerRegistrationData withDid(String newDid) {
        return new PartnerRegistrationData(name, city, streetName, countryAlpha2Code, bpn, shortName,
                region, streetAdditional, streetNumber, zipCode, uniqueIds, externalId, userDetails,
                companyRoles, newDid, agreements, documents, autoSubmit);
    }
}
