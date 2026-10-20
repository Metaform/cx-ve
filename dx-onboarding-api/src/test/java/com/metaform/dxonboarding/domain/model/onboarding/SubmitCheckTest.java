package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.FieldError;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.metaform.dxonboarding.domain.model.onboarding.Requests.NOW;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.data;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.gtc;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.pdfFile;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.uca;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.upload;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withPowerOfAttorneyUntil;
import static org.assertj.core.api.Assertions.assertThat;

/** What a draft needs to be submitted: its data, its declarations, the signed GTC and every signed UCA. */
class SubmitCheckTest {

    private static OnboardingRequest draft(OnboardingRequestData data, DocumentUpload... uploads) {
        var draft = OnboardingRequest.draft("req-1", "DX-OR-000001", "did:web:acme.example.com", data, null, NOW);
        for (var upload : uploads) {
            draft = draft.withDocument(UUID.randomUUID(), upload, NOW);
        }
        return draft;
    }

    @Test
    void aCompleteDraft_canBeSubmitted() {
        var draft = draft(data("did:web:acme.example.com", UseCase.PRODUCT_CONFORMITY), gtc(),
                uca(UseCase.PRODUCT_CONFORMITY));

        assertThat(SubmitCheck.violations(draft)).isEmpty();
    }

    @Test
    void anEmptyDraft_listsEverythingMissing() {
        var empty = draft(new OnboardingRequestData(null, null, null, null, null, null, null));

        assertThat(SubmitCheck.violations(empty)).containsExactly(
                new FieldError("legalEntity", "required"),
                new FieldError("legalPerson", "required"),
                new FieldError("gtc", "required"),
                new FieldError("documents", "the signed GTC document is required"),
                new FieldError("declarations", "required"));
    }

    @Test
    void theDataTheReviewWorksWith_isRequired() {
        var entity = new LegalEntity(" ", null, "DE", CompanyType.OTHER, null, null, null, List.of(),
                new Address(null, null, "Berlin", null, null), null);
        var data = new OnboardingRequestData(entity, new LegalPerson(null, "erika@example.org", null, null, null, null),
                List.of(), new ConsentDeclaration(null), List.of(), new Declarations(true, false, null), null);

        assertThat(SubmitCheck.violations(draft(data, gtc()))).extracting(FieldError::field).containsExactly(
                "legalEntity.preferredDid", "legalEntity.legalName", "legalEntity.companyTypeOther",
                "legalEntity.registrationNumbers", "legalEntity.legalAddress.street",
                "legalEntity.legalAddress.countryCode", "legalPerson.fullName", "gtc.versionNumber",
                "declarations.authorisedToAct", "declarations.evidenceMayBeRequested");
    }

    @Test
    void everyUseCase_needsItsSignedAgreement() {
        var draft = draft(data("did:web:acme.example.com", UseCase.PRODUCT_CONFORMITY, UseCase.EXPORT_CONTROL), gtc(),
                uca(UseCase.EXPORT_CONTROL));

        assertThat(SubmitCheck.violations(draft)).containsExactly(new FieldError("ucas[0]",
                "the signed use case agreement document for 'product-conformity' is required"));
    }

    @Test
    void aPowerOfAttorneyOnFile_needsItsExpiryDateStill() {
        var dated = withPowerOfAttorneyUntil(data("did:web:acme.example.com"), LocalDate.parse("2027-12-31"));
        var draft = draft(dated, gtc(), upload(DocumentType.POWER_OF_ATTORNEY, pdfFile("poa.pdf")))
                .withData(data("did:web:acme.example.com"), NOW);

        assertThat(SubmitCheck.violations(draft)).containsExactly(new FieldError(
                "legalPerson.powerOfAttorneyValidUntil", "required with a power-of-attorney document"));
    }
}
