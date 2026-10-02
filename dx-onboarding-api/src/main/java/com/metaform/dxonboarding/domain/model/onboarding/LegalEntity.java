package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.List;

/**
 * The applying company, as the applicant describes it.
 *
 * @param companyTypeOther what the company is, when {@code companyType} is {@code OTHER}
 * @param legalEntityId    VE EXTENSION, not part of the TSP's specification: the Decade-X-ID the
 *                         participant declares, as a Catena-X participant declares its BPN — a
 *                         participant hosted by the VE always does (its deployment needs it before
 *                         any approval), an external one may. The TSP honors it, unless another
 *                         participant holds it; a participant declaring none is assigned one on
 *                         approval.
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
