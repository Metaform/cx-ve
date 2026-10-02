package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.List;

/** An onboarding request as the applicant writes it — the {@code request} part of a submission. */
public record OnboardingRequestData(
        LegalEntity legalEntity,
        LegalPerson legalPerson,
        List<BusinessSite> businessSites,
        ConsentDeclaration gtc,
        List<UseCaseAgreement> ucas,
        Declarations declarations,
        String applicantReference) {

    public OnboardingRequestData {
        businessSites = businessSites == null ? List.of() : List.copyOf(businessSites);
        ucas = ucas == null ? List.of() : List.copyOf(ucas);
    }
}
