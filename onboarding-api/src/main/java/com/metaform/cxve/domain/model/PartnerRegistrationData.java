package com.metaform.cxve.domain.model;

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
