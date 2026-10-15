package com.metaform.dxonboarding.domain.model.onboarding;

/** An official registration number of a company or site, under one of the recognized schemes. */
public record RegistrationNumber(Scheme scheme, String value) {

    /** Named as they appear on the wire. */
    public enum Scheme {
        taxID,
        vatID,
        leiCode,
        duns,
        eori,
        euid,
        bvd,
        cageCode
    }
}
