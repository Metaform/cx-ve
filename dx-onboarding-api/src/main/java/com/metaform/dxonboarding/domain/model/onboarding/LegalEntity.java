package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.List;
import java.util.Locale;

/**
 * The applying company, as the applicant describes it.
 *
 * @param preferredDid     the participant's DID; kept trimmed and lower-cased ({@link #normalized()}),
 *                         as the TSP compares DIDs that way
 * @param companyTypeOther what the company is, when {@code companyType} is {@code OTHER}
 * @param legalEntityId    VE EXTENSION, not part of the TSP's specification: the DECADE-X-ID a
 *                         participant hosted by the VE was deployed with, out-of-band, before it
 *                         onboarded. Honored for those participants only; anybody else's is ignored
 *                         and the TSP assigns the id on approval.
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

    /** With its DID trimmed and lower-cased: {@code DID:Web:Acme.com} and {@code did:web:acme.com} are one DID. */
    public LegalEntity normalized() {
        var did = preferredDid == null ? null : preferredDid.trim().toLowerCase(Locale.ROOT);
        return new LegalEntity(did, legalName, registrationCountry, companyType, companyTypeOther, accreditationNumber,
                website, registrationNumbers, legalAddress, legalEntityId);
    }

    public LegalEntity withoutLegalEntityId() {
        return new LegalEntity(preferredDid, legalName, registrationCountry, companyType, companyTypeOther,
                accreditationNumber, website, registrationNumbers, legalAddress, null);
    }
}
