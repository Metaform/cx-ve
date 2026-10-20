package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.FieldError;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a request's data must satisfy to be stored at all, as a draft or as a correction of one:
 * the bounds of the TSP's schema ({@code maxLength}, {@code maxItems}), the fields it requires
 * inside an entry, and each use case once. Whether the request is complete is only checked when
 * it is submitted ({@link SubmitCheck}).
 */
public final class WriteCheck {

    static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

    private WriteCheck() {
    }

    /** Everything that keeps the data from being stored; empty when it can be. */
    public static List<FieldError> violations(OnboardingRequestData data) {
        var violations = new Violations();
        if (data == null) {
            violations.add("body", "required");
            return violations.list();
        }
        if (data.legalEntity() != null) {
            checkLegalEntity(data.legalEntity(), violations);
        }
        if (data.legalPerson() != null) {
            checkLegalPerson(data.legalPerson(), violations);
        }
        violations.maxItems("businessSites", data.businessSites(), 100);
        for (int i = 0; i < data.businessSites().size(); i++) {
            checkBusinessSite("businessSites[%d]".formatted(i), data.businessSites().get(i), violations);
        }
        if (data.gtc() != null) {
            violations.maxLength("gtc.versionNumber", data.gtc().versionNumber(), 32);
        }
        violations.maxItems("ucas", data.ucas(), 50);
        var useCases = new HashSet<UseCase>();
        for (int i = 0; i < data.ucas().size(); i++) {
            var uca = data.ucas().get(i);
            var path = "ucas[%d]".formatted(i);
            violations.required(path + ".useCaseId", uca.useCaseId());
            if (uca.useCaseId() != null && !useCases.add(uca.useCaseId())) {
                violations.add(path + ".useCaseId", "'%s' is listed more than once".formatted(uca.useCaseId().id()));
            }
            violations.maxLength(path + ".versionNumber", uca.versionNumber(), 32);
        }
        violations.maxLength("applicantReference", data.applicantReference(), 255);
        return violations.list();
    }

    private static void checkLegalEntity(LegalEntity entity, Violations violations) {
        violations.maxLength("legalEntity.preferredDid", entity.preferredDid(), 512);
        violations.maxLength("legalEntity.legalName", entity.legalName(), 255);
        violations.maxLength("legalEntity.registrationCountry", entity.registrationCountry(), 2);
        violations.maxLength("legalEntity.companyTypeOther", entity.companyTypeOther(), 255);
        violations.maxLength("legalEntity.accreditationNumber", entity.accreditationNumber(), 255);
        violations.maxLength("legalEntity.website", entity.website(), 2048);
        checkRegistrationNumbers("legalEntity.registrationNumbers", entity.registrationNumbers(), violations);
        checkAddress("legalEntity.legalAddress", entity.legalAddress(), violations);
    }

    private static void checkLegalPerson(LegalPerson person, Violations violations) {
        violations.maxLength("legalPerson.fullName", person.fullName(), 255);
        violations.maxLength("legalPerson.email", person.email(), 255);
        if (person.email() != null && !person.email().isBlank() && !EMAIL.matcher(person.email()).matches()) {
            violations.add("legalPerson.email", "not an email address");
        }
        violations.maxLength("legalPerson.role", person.role(), 255);
        violations.maxLength("legalPerson.phoneNumber", person.phoneNumber(), 32);
        checkAddress("legalPerson.headquartersAddress", person.headquartersAddress(), violations);
    }

    private static void checkBusinessSite(String path, BusinessSite site, Violations violations) {
        violations.maxLength(path + ".siteId", site.siteId(), 64);
        violations.maxLength(path + ".siteName", site.siteName(), 255);
        violations.maxLength(path + ".siteNameAlias", site.siteNameAlias(), 255);
        checkAddress(path + ".mainAddress", site.mainAddress(), violations);
        checkAddress(path + ".secondaryAddress", site.secondaryAddress(), violations);
        checkRegistrationNumbers(path + ".registrationNumbers", site.registrationNumbers(), violations);
    }

    private static void checkRegistrationNumbers(String path, List<RegistrationNumber> numbers, Violations violations) {
        violations.maxItems(path, numbers, 20);
        for (int i = 0; i < numbers.size(); i++) {
            var number = numbers.get(i);
            violations.required("%s[%d].scheme".formatted(path, i), number.scheme());
            violations.required("%s[%d].value".formatted(path, i), number.value());
            violations.maxLength("%s[%d].value".formatted(path, i), number.value(), 255);
        }
    }

    private static void checkAddress(String path, Address address, Violations violations) {
        if (address == null) {
            return;
        }
        violations.maxLength(path + ".street", address.street(), 255);
        violations.maxLength(path + ".postalCode", address.postalCode(), 32);
        violations.maxLength(path + ".locality", address.locality(), 255);
        violations.maxLength(path + ".countryCode", address.countryCode(), 2);
        violations.maxLength(path + ".countryName", address.countryName(), 255);
    }
}
