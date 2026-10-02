package com.metaform.dxonboarding.domain.model.onboarding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A submission is validated as a whole, and recognized again by its content. */
class SubmissionTest {

    private static final Address ADDRESS = new Address("Beispielstrasse 1", "10115", "Berlin", "DE", "Germany");

    static OnboardingRequestData request(CompanyType companyType, List<UseCaseAgreement> ucas) {
        return new OnboardingRequestData(
                new LegalEntity("did:web:example", "Example Aerospace GmbH", "DE", companyType, null, null, null,
                        List.of(new RegistrationNumber(RegistrationNumber.Scheme.vatID, "DE123456789")), ADDRESS),
                new LegalPerson("Erika Mustermann", "erika@example.org", "CEO", null, ADDRESS, null),
                List.of(),
                new ConsentDeclaration(UUID.fromString("0b8e1f0e-5c0a-4a52-9a53-7a1d2c3b4e5f"), "1.0", true),
                ucas,
                new Declarations(true, true, true),
                null);
    }

    static OnboardingRequestData request() {
        return request(CompanyType.LIMITED_LIABILITY_COMPANY,
                List.of(new UseCaseAgreement("export-control", "Export Control", UUID.randomUUID(), "v1.0", true)));
    }

    static SubmittedDocument pdf(String part) {
        return new SubmittedDocument(part, part + ".pdf", "application/pdf", ("%PDF " + part).getBytes());
    }

    static List<SubmittedDocument> documents() {
        return List.of(pdf(Submission.GTC_DOCUMENT), pdf("ucaDocument[export-control]"));
    }

    @Test
    void aCompleteSubmissionHasNoViolations() {
        assertThat(new Submission(request(), documents()).violations()).isEmpty();
    }

    @Test
    void theRequestAndTheGtcDocumentAreRequired() {
        assertThat(new Submission(null, List.of()).violations())
                .containsExactly("request: required", "gtcDocument: required");
    }

    @Test
    void everyUseCaseNeedsItsSignedAgreementAndNoOtherAgreementIsTaken() {
        var documents = new ArrayList<>(List.of(pdf(Submission.GTC_DOCUMENT), pdf("ucaDocument[critical-supply-chain]")));

        assertThat(new Submission(request(), documents).violations()).containsExactlyInAnyOrder(
                "ucaDocument[export-control]: required for use case 'export-control'",
                "ucaDocument[critical-supply-chain]: no entry of ucas has useCaseId 'critical-supply-chain'");
    }

    @Test
    void documentsMustBePdfPngOrJpegAndPartsMustBeKnown() {
        var documents = List.of(
                new SubmittedDocument(Submission.GTC_DOCUMENT, "gtc.txt", "text/plain", "x".getBytes()),
                new SubmittedDocument("ucaDocument[export-control]", "uca.png", "IMAGE/PNG; q=1", "x".getBytes()),
                pdf("gtcDocumnet"));

        assertThat(new Submission(request(), documents).violations()).containsExactlyInAnyOrder(
                "gtcDocument: content type 'text/plain' is not one of PDF, PNG or JPEG",
                "gtcDocumnet: not a document this request takes");
    }

    @Test
    void consentsAndDeclarationsMustBeAffirmed() {
        var request = request();
        var unaccepted = new OnboardingRequestData(request.legalEntity(), request.legalPerson(), List.of(),
                new ConsentDeclaration(request.gtc().versionId(), "1.0", false),
                List.of(new UseCaseAgreement("export-control", null, null, null, null)),
                new Declarations(true, false, true), null);

        assertThat(new Submission(unaccepted, documents()).violations()).containsExactlyInAnyOrder(
                "gtc.accepted: the GTC must be accepted",
                "ucas[0].accepted: the use case agreement must be accepted",
                "declarations.authorisedToAct: must be affirmed");
    }

    @Test
    void anOtherCompanyTypeMustBeNamed() {
        assertThat(new Submission(request(CompanyType.OTHER, List.of()), List.of(pdf(Submission.GTC_DOCUMENT)))
                .violations()).containsExactly("legalEntity.companyTypeOther: required");
    }

    @Test
    void aPowerOfAttorneyNeedsItsExpiryDate() {
        var documents = new ArrayList<>(documents());
        documents.add(pdf(Submission.POWER_OF_ATTORNEY_DOCUMENT));
        var request = request();

        assertThat(new Submission(request, documents).violations())
                .containsExactly("legalPerson.powerOfAttorneyValidUntil: required with a powerOfAttorneyDocument");

        var person = request.legalPerson();
        var dated = new OnboardingRequestData(request.legalEntity(), new LegalPerson(person.fullName(), person.email(),
                person.role(), person.phoneNumber(), person.headquartersAddress(), LocalDate.of(2027, 1, 1)),
                request.businessSites(), request.gtc(), request.ucas(), request.declarations(), null);
        assertThat(new Submission(dated, documents).violations()).isEmpty();
    }

    @Test
    void theFingerprintIsTheContentNotTheFileNames() {
        var request = request();
        var renamed = List.of(
                new SubmittedDocument(Submission.GTC_DOCUMENT, "other-name.pdf", "application/pdf",
                        ("%PDF " + Submission.GTC_DOCUMENT).getBytes()),
                pdf("ucaDocument[export-control]"));
        var changed = List.of(pdf(Submission.GTC_DOCUMENT),
                new SubmittedDocument("ucaDocument[export-control]", "uca.pdf", "application/pdf", "%PDF v2".getBytes()));

        var fingerprint = new Submission(request, documents()).fingerprint();

        assertThat(new Submission(request, renamed).fingerprint()).isEqualTo(fingerprint);
        assertThat(new Submission(request, changed).fingerprint()).isNotEqualTo(fingerprint);
    }
}
