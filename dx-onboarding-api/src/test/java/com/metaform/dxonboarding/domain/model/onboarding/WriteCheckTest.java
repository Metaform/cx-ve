package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.FieldError;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.metaform.dxonboarding.domain.model.onboarding.Requests.ADDRESS;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.data;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withLegalEntity;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withUcas;
import static org.assertj.core.api.Assertions.assertThat;

/** What any data must satisfy to be stored: the schema's bounds — not completeness, which waits for the submit. */
class WriteCheckTest {

    @Test
    void anEmptyDraft_canBeStored() {
        assertThat(WriteCheck.violations(new OnboardingRequestData(null, null, null, null, null, null, null))).isEmpty();
        assertThat(WriteCheck.violations(data("did:web:acme.example.com", UseCase.PRODUCT_CONFORMITY))).isEmpty();
    }

    @Test
    void textsAndLists_areBoundedByTheSchema() {
        var entity = new LegalEntity("did:web:" + "a".repeat(505), "n".repeat(256), "DEU", CompanyType.OTHER,
                "o".repeat(256), null, "https://" + "w".repeat(2041),
                Collections.nCopies(21, new RegistrationNumber(RegistrationNumber.Scheme.vatID, "DE1")),
                new Address("s".repeat(256), "1".repeat(33), "Berlin", "DEU", null), null);
        var data = withLegalEntity(data("did:web:acme.example.com"), entity);

        assertThat(WriteCheck.violations(data)).extracting(FieldError::field).containsExactly(
                "legalEntity.preferredDid", "legalEntity.legalName", "legalEntity.registrationCountry",
                "legalEntity.companyTypeOther", "legalEntity.website", "legalEntity.registrationNumbers",
                "legalEntity.legalAddress.street", "legalEntity.legalAddress.postalCode",
                "legalEntity.legalAddress.countryCode");
        assertThat(WriteCheck.violations(data)).first()
                .isEqualTo(new FieldError("legalEntity.preferredDid", "at most 512 characters"));
    }

    @Test
    void entries_needTheirRequiredFields() {
        var site = new BusinessSite("x".repeat(65), "HQ", null, ADDRESS, null,
                List.of(new RegistrationNumber(null, " ")));
        var base = data("did:web:acme.example.com");
        var data = new OnboardingRequestData(base.legalEntity(), base.legalPerson(), List.of(site), base.gtc(),
                List.of(new UseCaseAgreement(null, "1.0")), base.declarations(), base.applicantReference());

        assertThat(WriteCheck.violations(data)).containsExactly(
                new FieldError("businessSites[0].siteId", "at most 64 characters"),
                new FieldError("businessSites[0].registrationNumbers[0].scheme", "required"),
                new FieldError("businessSites[0].registrationNumbers[0].value", "required"),
                new FieldError("ucas[0].useCaseId", "required"));
    }

    @Test
    void eachUseCase_isListedOnce() {
        var data = withUcas(data("did:web:acme.example.com"), new UseCaseAgreement(UseCase.ENGINEERING, "1.0"),
                new UseCaseAgreement(UseCase.ENGINEERING, "2.0"));

        assertThat(WriteCheck.violations(data))
                .containsExactly(new FieldError("ucas[1].useCaseId", "'engineering' is listed more than once"));
    }

    @Test
    void anEmail_mustBeOne() {
        var base = data("did:web:acme.example.com");
        var data = new OnboardingRequestData(base.legalEntity(),
                new LegalPerson("Erika", "not an email", null, "+49 30 12345678901234567890123456", null, null),
                base.businessSites(), base.gtc(), base.ucas(), base.declarations(), base.applicantReference());

        assertThat(WriteCheck.violations(data)).containsExactly(
                new FieldError("legalPerson.email", "not an email address"),
                new FieldError("legalPerson.phoneNumber", "at most 32 characters"));
    }
}
