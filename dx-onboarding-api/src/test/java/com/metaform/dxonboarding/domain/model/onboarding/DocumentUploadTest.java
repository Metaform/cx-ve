package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.ProblemCode;
import org.junit.jupiter.api.Test;

import static com.metaform.dxonboarding.domain.model.onboarding.Requests.pdfFile;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.pdfOfSize;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.problem;
import static org.assertj.core.api.Assertions.assertThat;

/** A document's form: which fields each type takes, and which files the TSP accepts. */
class DocumentUploadTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Test
    void eachType_takesItsOwnFields() {
        var uca = DocumentUpload.of("uca", "product-conformity", null, pdfFile("uca.pdf"));
        var other = DocumentUpload.of("other", null, "Insurance certificate 2026", pdfFile("insurance.pdf"));
        var extract = DocumentUpload.of("registration-extract", "", " ", pdfFile("extract.pdf"));

        assertThat(uca.type()).isEqualTo(DocumentType.UCA);
        assertThat(uca.useCaseId()).isEqualTo(UseCase.PRODUCT_CONFORMITY);
        assertThat(other.label()).isEqualTo("Insurance certificate 2026");
        assertThat(extract.useCaseId()).isNull();
        assertThat(extract.label()).isNull();
    }

    @Test
    void aMissingUnknownOrMisplacedField_isRefused_withTheFirstProblemFound() {
        var invalid = ProblemCode.INVALID_DOCUMENT_UPLOAD;
        assertThat(problem(invalid, () -> DocumentUpload.of(null, null, null, pdfFile("x.pdf"))).detail())
                .isEqualTo("type: required");
        assertThat(problem(invalid, () -> DocumentUpload.of("contract", null, null, pdfFile("x.pdf"))).detail())
                .startsWith("type: 'contract' is not one of");
        assertThat(problem(invalid, () -> DocumentUpload.of("uca", null, null, pdfFile("x.pdf"))).detail())
                .isEqualTo("useCaseId: required for a uca");
        assertThat(problem(invalid, () -> DocumentUpload.of("uca", "ccm", null, pdfFile("x.pdf"))).detail())
                .contains("'ccm'");
        assertThat(problem(invalid, () -> DocumentUpload.of("gtc", "product-conformity", null, pdfFile("x.pdf"))).detail())
                .isEqualTo("useCaseId: only allowed for a uca");
        assertThat(problem(invalid, () -> DocumentUpload.of("other", null, null, pdfFile("x.pdf"))).detail())
                .isEqualTo("label: required for an other");
        assertThat(problem(invalid, () -> DocumentUpload.of("other", null, "x".repeat(256), pdfFile("x.pdf"))).detail())
                .isEqualTo("label: longer than 255 characters");
        assertThat(problem(invalid, () -> DocumentUpload.of("gtc", null, "GTC", pdfFile("x.pdf"))).detail())
                .isEqualTo("label: only allowed for an other");
    }

    @Test
    void theFile_mustBeAPdfPngOrJpeg_ofAtMost10Mb_thatIsWhatItSays() {
        new UploadedFile("scan.png", "image/png", PNG).check();
        new UploadedFile("scan.jpg", "image/jpeg; charset=binary", JPEG).check();
        pdfOfSize("max.pdf", 10 * 1024 * 1024).check();

        var invalid = ProblemCode.INVALID_DOCUMENT_UPLOAD;
        assertThat(problem(invalid, () -> new UploadedFile(null, null, null).check()).detail()).isEqualTo("file: required");
        assertThat(problem(invalid, () -> new UploadedFile("x.pdf", "application/pdf", new byte[0]).check()).detail())
                .isEqualTo("file: empty");
        assertThat(problem(invalid, () -> new UploadedFile(" ", "application/pdf", Requests.pdf("x")).check()).detail())
                .isEqualTo("file: a file name is required");
        problem(invalid, () -> new UploadedFile("x".repeat(252) + ".pdf", "application/pdf", Requests.pdf("x")).check());
        problem(ProblemCode.UNSUPPORTED_DOCUMENT_TYPE, () -> new UploadedFile("x.txt", "text/plain", PNG).check());
        problem(ProblemCode.UNSUPPORTED_DOCUMENT_TYPE, () -> new UploadedFile("x", null, PNG).check());
        problem(ProblemCode.CONTENT_TOO_LARGE, () -> pdfOfSize("big.pdf", 10 * 1024 * 1024 + 1).check());
        assertThat(problem(invalid, () -> new UploadedFile("x.pdf", "application/pdf", PNG).check()).detail())
                .isEqualTo("file: the content is not what its Content-Type 'application/pdf' says");
        problem(invalid, () -> new UploadedFile("x.png", "image/png", JPEG).check());
        problem(invalid, () -> new UploadedFile("x.jpg", "image/jpeg", new byte[]{(byte) 0xFF}).check());
    }
}
