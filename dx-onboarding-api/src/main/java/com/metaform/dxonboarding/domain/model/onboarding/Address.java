package com.metaform.dxonboarding.domain.model.onboarding;

/** A postal address. */
public record Address(String street, String postalCode, String locality, String countryCode, String countryName) {
}
