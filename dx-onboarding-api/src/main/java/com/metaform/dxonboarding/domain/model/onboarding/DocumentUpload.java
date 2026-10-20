package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.ProblemException;
import java.util.Arrays;

/**
 * A document to add to a draft, as its form describes it: its type, the use case of a {@code uca}
 * and the label of an {@code other}, and the file.
 */
public record DocumentUpload(DocumentType type, UseCase useCaseId, String label, UploadedFile file) {

    public static final int MAX_LABEL_LENGTH = 255;

    /**
     * Reads the form's fields, refusing the first one that is missing, unknown or not allowed for
     * the type, and then a file the TSP does not take ({@link UploadedFile#check()}).
     */
    public static DocumentUpload of(String type, String useCaseId, String label, UploadedFile file) {
        if (blank(type)) {
            throw ProblemException.invalidUpload("type: required");
        }
        var documentType = DocumentType.fromWireName(type).orElseThrow(() -> ProblemException.invalidUpload(
                "type: '%s' is not one of %s".formatted(type,
                        Arrays.stream(DocumentType.values()).map(DocumentType::wireName).toList())));
        UseCase useCase = null;
        if (documentType == DocumentType.UCA) {
            if (blank(useCaseId)) {
                throw ProblemException.invalidUpload("useCaseId: required for a uca");
            }
            useCase = UseCase.fromId(useCaseId).orElseThrow(() -> ProblemException.invalidUpload(
                    "useCaseId: '%s' is not one of the TSP's use cases".formatted(useCaseId)));
        } else if (!blank(useCaseId)) {
            throw ProblemException.invalidUpload("useCaseId: only allowed for a uca");
        }
        checkLabel(documentType, label);
        file.check();
        return new DocumentUpload(documentType, useCase, blank(label) ? null : label, file);
    }

    /** An {@code other} must say what it is, in at most 255 characters; no other type takes a label. */
    static void checkLabel(DocumentType type, String label) {
        if (type == DocumentType.OTHER) {
            if (blank(label)) {
                throw ProblemException.invalidUpload("label: required for an other");
            }
            if (label.length() > MAX_LABEL_LENGTH) {
                throw ProblemException.invalidUpload("label: longer than 255 characters");
            }
        } else if (!blank(label)) {
            throw ProblemException.invalidUpload("label: only allowed for an other");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
