package com.metaform.dxonboarding.domain.model.onboarding;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * What a document of a request is. Every type but {@code OTHER} is a slot holding one document per
 * request — per use case, for a {@code UCA}.
 */
public enum DocumentType {
    /** The General Terms and Conditions signed by the applicant, for the version named in {@code gtc}. */
    GTC("gtc"),
    /** The signed use case agreement of one of the request's {@code ucas}. */
    UCA("uca"),
    /** An extract from the company register, backing {@code legalEntity.registrationNumbers}. */
    REGISTRATION_EXTRACT("registration-extract"),
    /** Shows that {@code legalPerson} may act for the company, until {@code powerOfAttorneyValidUntil}. */
    POWER_OF_ATTORNEY("power-of-attorney"),
    /** Anything else that supports the request, named by its label. */
    OTHER("other");

    private final String wireName;

    DocumentType(String wireName) {
        this.wireName = wireName;
    }

    /** The name on the wire, e.g. {@code registration-extract}. */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    public static Optional<DocumentType> fromWireName(String wireName) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(wireName)).findFirst();
    }
}
