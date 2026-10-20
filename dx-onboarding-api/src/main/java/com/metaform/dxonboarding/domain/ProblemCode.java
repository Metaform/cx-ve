package com.metaform.dxonboarding.domain;

/**
 * What went wrong with a call, as the TSP's API names it: the problem {@code type} is
 * {@code urn:decade-x:tsp:problem:<code>}, and each code comes with its HTTP status.
 */
public enum ProblemCode {
    INVALID_REQUEST("invalid-request", 400, "Invalid request"),
    MALFORMED_IF_MATCH("malformed-if-match", 400, "Malformed If-Match"),
    PRECONDITION_REQUIRED("precondition-required", 428, "If-Match required"),
    VERSION_CONFLICT("version-conflict", 409, "Version conflict"),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", 409, "Idempotency key reused"),
    NOT_FOUND("not-found", 404, "Not found"),
    UNAUTHORIZED("unauthorized", 401, "Unauthorized"),
    INVALID_ONBOARDING_REQUEST_STATE("invalid-onboarding-request-state", 422, "Request no longer a draft"),
    ONBOARDING_REQUEST_SUBMIT_REJECTED("onboarding-request-submit-rejected", 422, "Submit rejected"),
    CONNECTOR_DRAFT_LIMIT_REACHED("connector-draft-limit-reached", 422, "Draft limit reached"),
    CONNECTOR_PENDING_REQUEST_LIMIT_REACHED("connector-pending-request-limit-reached", 422,
            "Pending request limit reached"),
    INVALID_DOCUMENT_UPLOAD("invalid-document-upload", 400, "Invalid document upload"),
    UNSUPPORTED_DOCUMENT_TYPE("unsupported-document-type", 415, "Unsupported document type"),
    CONTENT_TOO_LARGE("content-too-large", 413, "Content too large"),
    DOCUMENT_SLOT_TAKEN("document-slot-taken", 409, "Document slot taken"),
    USE_CASE_NOT_IN_REQUEST("use-case-not-in-request", 422, "Use case not in request"),
    OTHER_DOCUMENT_LIMIT_REACHED("other-document-limit-reached", 422, "Other document limit reached"),
    DOCUMENT_QUOTA_EXCEEDED("document-quota-exceeded", 422, "Document quota exceeded"),
    INTERNAL_ERROR("internal-error", 500, "Internal error");

    public static final String TYPE_PREFIX = "urn:decade-x:tsp:problem:";

    private final String code;
    private final int status;
    private final String title;

    ProblemCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The problem {@code type}: {@code urn:decade-x:tsp:problem:<code>}. */
    public String type() {
        return TYPE_PREFIX + code;
    }
}
