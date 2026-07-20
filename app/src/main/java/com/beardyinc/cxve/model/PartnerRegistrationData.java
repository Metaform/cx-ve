package com.beardyinc.cxve.model;

import java.util.List;

public record PartnerRegistrationData(
        String name,
        String city,
        String streetName,
        String countryAlpha2Code,
        String bpn,
        String shortName,
        String region,
        String streetAdditional,
        String streetNumber,
        String zipCode,
        List<CompanyUniqueIdData> uniqueIds,
        String externalId,
        List<UserDetailData> userDetails,
        List<CompanyRoleId> companyRoles,
        String did,
        List<AgreementConsentData> agreements,
        List<DocumentUpload> documents,
        Boolean autoSubmit
) {
}
