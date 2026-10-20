package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.FieldError;
import java.util.List;

/**
 * What a draft needs to be submitted: the data the review works with, the applicant's
 * declarations affirmed, the signed GTC and a signed use case agreement for every entry of
 * {@code ucas}. Whether its DID is free and resolves needs more than the draft, so the service
 * checks that.
 */
public final class SubmitCheck {

    private SubmitCheck() {
    }

    /** Everything that keeps the draft from being submitted; empty when it is complete. */
    public static List<FieldError> violations(OnboardingRequest draft) {
        var violations = new Violations();
        var data = draft.data();
        checkLegalEntity(data.legalEntity(), violations);
        checkLegalPerson(data.legalPerson(), draft, violations);
        if (data.gtc() == null) {
            violations.add("gtc", "required");
        } else {
            violations.required("gtc.versionNumber", data.gtc().versionNumber());
        }
        if (draft.document(DocumentType.GTC, null).isEmpty()) {
            violations.add("documents", "the signed GTC document is required");
        }
        for (int i = 0; i < data.ucas().size(); i++) {
            var useCase = data.ucas().get(i).useCaseId();
            if (draft.document(DocumentType.UCA, useCase).isEmpty()) {
                violations.add("ucas[%d]".formatted(i),
                        "the signed use case agreement document for '%s' is required".formatted(useCase.id()));
            }
        }
        var declarations = data.declarations();
        if (declarations == null) {
            violations.add("declarations", "required");
        } else {
            violations.affirmed("declarations.informationAccurate", declarations.informationAccurate());
            violations.affirmed("declarations.authorisedToAct", declarations.authorisedToAct());
            violations.affirmed("declarations.evidenceMayBeRequested", declarations.evidenceMayBeRequested());
        }
        return violations.list();
    }

    private static void checkLegalEntity(LegalEntity entity, Violations violations) {
        if (entity == null) {
            violations.add("legalEntity", "required");
            return;
        }
        violations.required("legalEntity.preferredDid", entity.preferredDid());
        violations.required("legalEntity.legalName", entity.legalName());
        violations.required("legalEntity.registrationCountry", entity.registrationCountry());
        violations.required("legalEntity.companyType", entity.companyType());
        if (entity.companyType() == CompanyType.OTHER) {
            violations.required("legalEntity.companyTypeOther", entity.companyTypeOther());
        }
        if (entity.registrationNumbers().isEmpty()) {
            violations.add("legalEntity.registrationNumbers", "at least one is required");
        }
        var address = entity.legalAddress();
        if (address == null) {
            violations.add("legalEntity.legalAddress", "required");
        } else {
            violations.required("legalEntity.legalAddress.street", address.street());
            violations.required("legalEntity.legalAddress.locality", address.locality());
            violations.required("legalEntity.legalAddress.countryCode", address.countryCode());
        }
    }

    private static void checkLegalPerson(LegalPerson person, OnboardingRequest draft, Violations violations) {
        if (person == null) {
            violations.add("legalPerson", "required");
            return;
        }
        violations.required("legalPerson.fullName", person.fullName());
        violations.required("legalPerson.email", person.email());
        if (draft.document(DocumentType.POWER_OF_ATTORNEY, null).isPresent() && person.powerOfAttorneyValidUntil() == null) {
            violations.add("legalPerson.powerOfAttorneyValidUntil", "required with a power-of-attorney document");
        }
    }
}
