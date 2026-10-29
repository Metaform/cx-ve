package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.ProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Onboarding requests and documents for the tests: complete unless a test says otherwise. */
public final class Requests {

    public static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    public static final Address ADDRESS = new Address("Beispielstrasse 1", "10115", "Berlin", "DE", "Germany");

    private Requests() {
    }

    /** Everything a submit needs but the documents: the GTC at version 1.0, and the use cases at 1.0. */
    public static OnboardingRequestData data(String did, UseCase... useCases) {
        return new OnboardingRequestData(
                new LegalEntity(did, "Acme Corp", "DE", CompanyType.LIMITED_LIABILITY_COMPANY, null, null, null,
                        List.of(new RegistrationNumber(RegistrationNumber.Scheme.vatID, "DE123456789")), ADDRESS,
                        null),
                new LegalPerson("Erika Mustermann", "erika@example.org", "CEO", null, null, null),
                List.of(),
                new ConsentDeclaration("1.0"),
                Arrays.stream(useCases).map(useCase -> new UseCaseAgreement(useCase, "1.0")).toList(),
                new Declarations(true, true, true),
                "ref-1");
    }

    public static OnboardingRequestData withGtc(OnboardingRequestData data, String versionNumber) {
        return new OnboardingRequestData(data.legalEntity(), data.legalPerson(), data.businessSites(),
                new ConsentDeclaration(versionNumber), data.ucas(), data.declarations(), data.applicantReference());
    }

    public static OnboardingRequestData withUcas(OnboardingRequestData data, UseCaseAgreement... ucas) {
        return new OnboardingRequestData(data.legalEntity(), data.legalPerson(), data.businessSites(), data.gtc(),
                List.of(ucas), data.declarations(), data.applicantReference());
    }

    public static OnboardingRequestData withLegalEntity(OnboardingRequestData data, LegalEntity legalEntity) {
        return new OnboardingRequestData(legalEntity, data.legalPerson(), data.businessSites(), data.gtc(), data.ucas(),
                data.declarations(), data.applicantReference());
    }

    public static OnboardingRequestData withPowerOfAttorneyUntil(OnboardingRequestData data, LocalDate validUntil) {
        var person = data.legalPerson();
        return new OnboardingRequestData(data.legalEntity(), new LegalPerson(person.fullName(), person.email(),
                person.role(), person.phoneNumber(), person.headquartersAddress(), validUntil), data.businessSites(),
                data.gtc(), data.ucas(), data.declarations(), data.applicantReference());
    }

    public static OnboardingRequestData withApplicantReference(OnboardingRequestData data, String reference) {
        return new OnboardingRequestData(data.legalEntity(), data.legalPerson(), data.businessSites(), data.gtc(),
                data.ucas(), data.declarations(), reference);
    }

    /** The legal entity with the given DECADE-X-ID declared (the VE's out-of-band extension). */
    public static OnboardingRequestData declaring(OnboardingRequestData data, String legalEntityId) {
        var entity = data.legalEntity();
        return withLegalEntity(data, new LegalEntity(entity.preferredDid(), entity.legalName(),
                entity.registrationCountry(), entity.companyType(), entity.companyTypeOther(),
                entity.accreditationNumber(), entity.website(), entity.registrationNumbers(), entity.legalAddress(),
                legalEntityId));
    }

    public static byte[] pdf(String text) {
        return ("%PDF-1.4\n" + text).getBytes(StandardCharsets.US_ASCII);
    }

    public static UploadedFile pdfFile(String filename) {
        return new UploadedFile(filename, "application/pdf", pdf(filename));
    }

    /** A PDF of the given size, header included. */
    public static UploadedFile pdfOfSize(String filename, int bytes) {
        var content = new byte[bytes];
        System.arraycopy(pdf(""), 0, content, 0, pdf("").length);
        return new UploadedFile(filename, "application/pdf", content);
    }

    public static DocumentUpload gtc() {
        return new DocumentUpload(DocumentType.GTC, null, null, pdfFile("gtc.pdf"));
    }

    public static DocumentUpload uca(UseCase useCase) {
        return new DocumentUpload(DocumentType.UCA, useCase, null, pdfFile("uca-" + useCase.id() + ".pdf"));
    }

    public static DocumentUpload other(String label) {
        return new DocumentUpload(DocumentType.OTHER, null, label, pdfFile(label + ".pdf"));
    }

    public static DocumentUpload upload(DocumentType type, UploadedFile file) {
        return new DocumentUpload(type, null, null, file);
    }

    /** Runs the call, which must be refused with the given problem; returns the refusal for a closer look. */
    public static ProblemException problem(ProblemCode code, ThrowableAssert.ThrowingCallable call) {
        var thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(ProblemException.class);
        var problem = (ProblemException) thrown;
        assertThat(problem.code()).as(problem.getMessage()).isEqualTo(code);
        return problem;
    }
}
