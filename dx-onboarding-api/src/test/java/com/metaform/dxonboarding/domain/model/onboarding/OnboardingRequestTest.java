package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.ProblemCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.metaform.dxonboarding.domain.model.onboarding.Requests.NOW;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.data;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.gtc;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.other;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.pdfFile;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.pdfOfSize;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.problem;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.uca;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.upload;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withGtc;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withPowerOfAttorneyUntil;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withUcas;
import static com.metaform.dxonboarding.domain.model.onboarding.UseCase.EXPORT_CONTROL;
import static com.metaform.dxonboarding.domain.model.onboarding.UseCase.PRODUCT_CONFORMITY;
import static org.assertj.core.api.Assertions.assertThat;

/** A draft's own rules: its versions, the slots of its documents and their limits, and what a correction drops. */
class OnboardingRequestTest {

    private static final Instant LATER = NOW.plusSeconds(60);
    private static final int MB = 1024 * 1024;

    private static OnboardingRequest draft(OnboardingRequestData data) {
        return OnboardingRequest.draft("req-1", "DX-OR-000001", "did:web:acme.example.com", data, null, NOW);
    }

    private static OnboardingRequest withDocuments(OnboardingRequest request, DocumentUpload... uploads) {
        for (var upload : uploads) {
            request = request.withDocument(UUID.randomUUID(), upload, NOW);
        }
        return request;
    }

    @Test
    void aDraft_startsAtVersion1_withoutDocuments() {
        var draft = draft(data("did:web:acme.example.com"));

        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo(OnboardingStatus.DRAFT);
        assertThat(draft.documents()).isEmpty();
        assertThat(draft.submittedAt()).isNull();
        assertThat(draft.replay().createdWith()).isEqualTo(draft.data());
    }

    @Test
    void everyChange_makesANewVersion_andAnOutdatedOneIsAConflict() {
        var draft = draft(data("did:web:acme.example.com", PRODUCT_CONFORMITY));
        var gtcId = UUID.randomUUID();

        var added = draft.withDocument(gtcId, gtc(), LATER);
        var replaced = added.withReplacedFile(gtcId, null, pdfFile("gtc-2.pdf"), LATER);
        var removed = replaced.withoutDocument(gtcId, LATER);
        var corrected = removed.withData(data("did:web:acme.example.com"), LATER);
        var submitted = corrected.submitted(LATER, null);

        assertThat(added.version()).isEqualTo(2);
        assertThat(replaced.version()).isEqualTo(3);
        assertThat(removed.version()).isEqualTo(4);
        assertThat(corrected.version()).isEqualTo(5);
        assertThat(submitted.version()).isEqualTo(6);
        assertThat(corrected.updatedAt()).isEqualTo(LATER);
        assertThat(problem(ProblemCode.VERSION_CONFLICT, () -> corrected.requireVersion(4)).detail())
                .contains("\"4\"", "\"5\"");
    }

    @Test
    void aCorrection_keepsTheDocuments_butTheGtcWhenItsVersionChanged() {
        var draft = withDocuments(draft(data("did:web:acme.example.com", PRODUCT_CONFORMITY)), gtc(),
                uca(PRODUCT_CONFORMITY), other("Insurance"));

        var sameVersions = draft.withData(withGtc(draft.data(), "1.0"), LATER);
        var newGtc = draft.withData(withGtc(draft.data(), "2.0"), LATER);

        assertThat(sameVersions.documents()).hasSize(3);
        assertThat(newGtc.documents()).extracting(OnboardingDocument::type)
                .containsExactly(DocumentType.UCA, DocumentType.OTHER);
    }

    @Test
    void aCorrection_dropsAUca_whenItsUseCaseLeaves_orItsVersionChanged() {
        var draft = withDocuments(draft(data("did:web:acme.example.com", PRODUCT_CONFORMITY, EXPORT_CONTROL)),
                uca(PRODUCT_CONFORMITY), uca(EXPORT_CONTROL));

        var corrected = draft.withData(withUcas(draft.data(), new UseCaseAgreement(PRODUCT_CONFORMITY, "2.0"),
                new UseCaseAgreement(UseCase.ENGINEERING, "1.0")), LATER);

        assertThat(corrected.documents()).isEmpty();
        assertThat(draft.staleDocuments(corrected.data())).hasSize(2);
        var kept = draft.withData(withUcas(draft.data(), new UseCaseAgreement(EXPORT_CONTROL, "1.0")), LATER);
        assertThat(kept.documents()).extracting(OnboardingDocument::useCaseId).containsExactly(EXPORT_CONTROL);
    }

    @Test
    void aSlotHoldsOneDocument_andTheRefusalNamesTheOneOnFile() {
        var gtcId = UUID.randomUUID();
        var draft = draft(data("did:web:acme.example.com", PRODUCT_CONFORMITY, EXPORT_CONTROL))
                .withDocument(gtcId, gtc(), NOW)
                .withDocument(UUID.randomUUID(), uca(PRODUCT_CONFORMITY), NOW);

        var refusal = problem(ProblemCode.DOCUMENT_SLOT_TAKEN, () -> draft.withDocument(UUID.randomUUID(), gtc(), NOW));

        assertThat(refusal.documentId()).isEqualTo(gtcId);
        // a use case agreement's slot is its use case's
        assertThat(draft.withDocument(UUID.randomUUID(), uca(EXPORT_CONTROL), NOW).documents()).hasSize(3);
        problem(ProblemCode.DOCUMENT_SLOT_TAKEN, () -> draft.withDocument(UUID.randomUUID(), uca(PRODUCT_CONFORMITY), NOW));
    }

    @Test
    void aUca_mustBeForOneOfTheRequestsUseCases() {
        var draft = draft(data("did:web:acme.example.com", PRODUCT_CONFORMITY));

        var refusal = problem(ProblemCode.USE_CASE_NOT_IN_REQUEST,
                () -> draft.withDocument(UUID.randomUUID(), uca(EXPORT_CONTROL), NOW));

        assertThat(refusal.detail()).contains("export-control");
    }

    @Test
    void otherDocuments_repeat_upTo5() {
        var draft = withDocuments(draft(data("did:web:acme.example.com")), other("a"), other("b"), other("c"),
                other("d"), other("e"));

        assertThat(draft.documents(DocumentType.OTHER)).extracting(OnboardingDocument::label)
                .containsExactly("a", "b", "c", "d", "e");
        problem(ProblemCode.OTHER_DOCUMENT_LIMIT_REACHED, () -> draft.withDocument(UUID.randomUUID(), other("f"), NOW));
    }

    @Test
    void theDocuments_weighAtMost50Mb_countingAReplacedFileInsteadOfTheOldOne() {
        var tenMb = pdfOfSize("big.pdf", 10 * MB);
        var draft = draft(data("did:web:acme.example.com"));
        for (var label : new String[]{"a", "b", "c", "d"}) {
            draft = draft.withDocument(UUID.randomUUID(), new DocumentUpload(DocumentType.OTHER, null, label, tenMb), NOW);
        }
        draft = draft.withDocument(UUID.randomUUID(), upload(DocumentType.GTC, pdfOfSize("gtc.pdf", 9 * MB)), NOW);
        var full = draft.withDocument(UUID.randomUUID(),
                upload(DocumentType.REGISTRATION_EXTRACT, pdfOfSize("extract.pdf", MB)), NOW);
        assertThat(full.documentsBytes()).isEqualTo(50L * MB);

        problem(ProblemCode.DOCUMENT_QUOTA_EXCEEDED,
                () -> full.withDocument(UUID.randomUUID(), new DocumentUpload(DocumentType.OTHER, null, "e", pdfFile("e.pdf")), NOW));
        var gtcId = full.document(DocumentType.GTC, null).orElseThrow().documentId();
        assertThat(full.withReplacedFile(gtcId, null, pdfOfSize("gtc.pdf", 9 * MB), LATER).documentsBytes())
                .isEqualTo(50L * MB);
        problem(ProblemCode.DOCUMENT_QUOTA_EXCEEDED,
                () -> full.withReplacedFile(gtcId, null, pdfOfSize("gtc.pdf", 9 * MB + 1), LATER));
    }

    @Test
    void aPowerOfAttorney_needsItsExpiryDateFirst_andRecordsIt() {
        var undated = draft(data("did:web:acme.example.com"));
        var powerOfAttorney = upload(DocumentType.POWER_OF_ATTORNEY, pdfFile("poa.pdf"));

        var refusal = problem(ProblemCode.INVALID_DOCUMENT_UPLOAD,
                () -> undated.withDocument(UUID.randomUUID(), powerOfAttorney, NOW));
        assertThat(refusal.detail()).contains("legalPerson.powerOfAttorneyValidUntil");

        var dated = undated.withData(withPowerOfAttorneyUntil(undated.data(), LocalDate.parse("2027-12-31")), NOW)
                .withDocument(UUID.randomUUID(), powerOfAttorney, NOW);
        assertThat(dated.document(DocumentType.POWER_OF_ATTORNEY, null).orElseThrow().validUntil())
                .isEqualTo(LocalDate.parse("2027-12-31"));
    }

    @Test
    void aReplacedFile_keepsTheDocumentsIdTypeAndUseCase_andAnOthersLabelMayChange() {
        var ucaId = UUID.randomUUID();
        var otherId = UUID.randomUUID();
        var draft = draft(data("did:web:acme.example.com", PRODUCT_CONFORMITY))
                .withDocument(ucaId, uca(PRODUCT_CONFORMITY), NOW)
                .withDocument(otherId, other("Insurance"), NOW);

        var replaced = draft.withReplacedFile(ucaId, null,
                new UploadedFile("signed.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1}),
                LATER);
        var relabeled = draft.withReplacedFile(otherId, "Insurance 2027", pdfFile("insurance.pdf"), LATER);

        var uca = replaced.document(ucaId).orElseThrow();
        assertThat(uca.type()).isEqualTo(DocumentType.UCA);
        assertThat(uca.useCaseId()).isEqualTo(PRODUCT_CONFORMITY);
        assertThat(uca.filename()).isEqualTo("signed.png");
        assertThat(uca.contentType()).isEqualTo("image/png");
        assertThat(uca.uploadedAt()).isEqualTo(LATER);
        assertThat(replaced.documents()).extracting(OnboardingDocument::documentId).containsExactly(ucaId, otherId);
        assertThat(relabeled.document(otherId).orElseThrow().label()).isEqualTo("Insurance 2027");
        problem(ProblemCode.INVALID_DOCUMENT_UPLOAD, () -> draft.withReplacedFile(otherId, null, pdfFile("x.pdf"), LATER));
        problem(ProblemCode.INVALID_DOCUMENT_UPLOAD, () -> draft.withReplacedFile(ucaId, "label", pdfFile("x.pdf"), LATER));
        problem(ProblemCode.UNSUPPORTED_DOCUMENT_TYPE,
                () -> draft.withReplacedFile(ucaId, null, new UploadedFile("x.txt", "text/plain", new byte[]{1}), LATER));
    }

    @Test
    void anUnknownDocument_isNotFound() {
        var draft = draft(data("did:web:acme.example.com"));

        problem(ProblemCode.NOT_FOUND, () -> draft.withoutDocument(UUID.randomUUID(), NOW));
        problem(ProblemCode.NOT_FOUND, () -> draft.withReplacedFile(UUID.randomUUID(), null, pdfFile("x.pdf"), NOW));
    }

    @Test
    void aSubmittedRequest_belongsToTheReview_andCanNoLongerBeChanged() {
        var gtcId = UUID.randomUUID();
        var submitted = draft(data("did:web:acme.example.com")).withDocument(gtcId, gtc(), NOW).submitted(LATER, "key-1");

        assertThat(submitted.status()).isEqualTo(OnboardingStatus.SUBMITTED);
        assertThat(submitted.submittedAt()).isEqualTo(LATER);
        assertThat(submitted.replay().submitKey()).isEqualTo("key-1");
        var state = ProblemCode.INVALID_ONBOARDING_REQUEST_STATE;
        problem(state, () -> submitted.withData(submitted.data(), LATER));
        problem(state, () -> submitted.withDocument(UUID.randomUUID(), other("x"), LATER));
        problem(state, () -> submitted.withReplacedFile(gtcId, null, pdfFile("x.pdf"), LATER));
        problem(state, () -> submitted.withoutDocument(gtcId, LATER));
        problem(state, () -> submitted.submitted(LATER, null));
    }

    @Test
    void theReviewsDecision_makesNewVersionsToo() {
        var submitted = draft(data("did:web:acme.example.com")).submitted(NOW, null);

        var inProgress = submitted.approvalInProgress("DX-12345678");
        var approved = inProgress.approved(LATER);

        assertThat(inProgress.version()).isEqualTo(submitted.version() + 1);
        assertThat(approved.version()).isEqualTo(submitted.version() + 2);
        assertThat(approved.legalEntityId()).isEqualTo("DX-12345678");
        assertThat(approved.holdsLegalEntityId()).isTrue();
    }
}
