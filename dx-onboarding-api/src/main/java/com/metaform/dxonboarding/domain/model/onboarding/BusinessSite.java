package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.List;

/** A site of the applying company. */
public record BusinessSite(
        String siteId,
        String siteName,
        String siteNameAlias,
        Address mainAddress,
        Address secondaryAddress,
        List<RegistrationNumber> registrationNumbers) {

    public BusinessSite {
        registrationNumbers = registrationNumbers == null ? List.of() : List.copyOf(registrationNumbers);
    }
}
