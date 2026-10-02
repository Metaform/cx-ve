package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.List;

/**
 * The applying company, as the applicant describes it.
 *
 * @param companyTypeOther what the company is, when {@code companyType} is {@code OTHER}
 * @param legalEntityId    VE EXTENSION, not part of the TSP's specification: the Decade-X-ID a
 *                         participant hosted by the VE already holds — the VE assigns it up front,
 *                         because the participant's deployment needs it before the TSP would. The
 *                         TSP honors it only for a participant hosted here, and assigns one to
 *                         every other participant on approval.
 */
public record LegalEntity(
        String preferredDid,
        String legalName,
        String registrationCountry,
        CompanyType companyType,
        String companyTypeOther,
        String accreditationNumber,
        String website,
        List<RegistrationNumber> registrationNumbers,
        Address legalAddress,
        String legalEntityId) {

    public LegalEntity {
        registrationNumbers = registrationNumbers == null ? List.of() : List.copyOf(registrationNumbers);
    }
}
