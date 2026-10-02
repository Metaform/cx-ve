package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.List;

/**
 * The applying company, as the applicant describes it.
 *
 * @param companyTypeOther what the company is, when {@code companyType} is {@code OTHER}
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
        Address legalAddress) {

    public LegalEntity {
        registrationNumbers = registrationNumbers == null ? List.of() : List.copyOf(registrationNumbers);
    }
}
