package com.metaform.dxonboarding.domain;

import java.util.List;
import java.util.UUID;

/**
 * A call refused for one of the API's documented reasons; nothing it asked for was changed. The
 * web layer answers it as an RFC 9457 problem of its {@link ProblemCode}.
 *
 * @see ProblemCode
 */
public class ProblemException extends RuntimeException {

    private final ProblemCode code;
    private final List<FieldError> errors;
    private final UUID documentId;

    public ProblemException(ProblemCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public ProblemException(ProblemCode code, String detail, List<FieldError> errors, UUID documentId) {
        super(detail);
        this.code = code;
        this.errors = List.copyOf(errors);
        this.documentId = documentId;
    }

    /** A body refused by validation: 400, every problem listed. */
    public static ProblemException invalidRequest(List<FieldError> errors) {
        return new ProblemException(ProblemCode.INVALID_REQUEST, "The request is not valid: " + describe(errors),
                errors, null);
    }

    /** A draft that cannot be submitted: 422, every problem listed; the draft is left unchanged. */
    public static ProblemException submitRejected(List<FieldError> errors) {
        return new ProblemException(ProblemCode.ONBOARDING_REQUEST_SUBMIT_REJECTED,
                "The draft cannot be submitted: " + describe(errors), errors, null);
    }

    public static ProblemException notFound(String detail) {
        return new ProblemException(ProblemCode.NOT_FOUND, detail);
    }

    /** A document field or file that is not valid: 400, the first problem found. */
    public static ProblemException invalidUpload(String detail) {
        return new ProblemException(ProblemCode.INVALID_DOCUMENT_UPLOAD, detail);
    }

    public ProblemCode code() {
        return code;
    }

    public String detail() {
        return getMessage();
    }

    /** The field problems of a validation failure; empty otherwise. */
    public List<FieldError> errors() {
        return errors;
    }

    /** The document on file, for {@link ProblemCode#DOCUMENT_SLOT_TAKEN}; null otherwise. */
    public UUID documentId() {
        return documentId;
    }

    private static String describe(List<FieldError> errors) {
        return String.join("; ", errors.stream().map(error -> error.field() + ": " + error.message()).toList());
    }
}
