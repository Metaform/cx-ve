package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.ProblemException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An onboarding request and where it stands. It starts as a {@code DRAFT}, which its applicant
 * corrects and adds documents to; once submitted, it belongs to the review and no longer changes
 * but for the review's decision. Every change makes a new {@link #version()}, which a change must
 * name to be accepted ({@link #requireVersion}).
 *
 * @param id            the id the applicant reads the request back by
 * @param businessId    a human-readable reference, e.g. for a TSP operator
 * @param connectorId   the identity of the connector that created it; only that connector sees it
 * @param submittedAt   null while it is a draft
 * @param documents     its documents, oldest first
 * @param legalEntityId the participant's DECADE-X-ID — the TSP's id of the legal entity; null
 *                      until approval assigns it
 * @param decision      null until a decision is taken
 * @param replay        what recognizes a call sent again
 */
public record OnboardingRequest(
        String id,
        String businessId,
        String connectorId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt,
        OnboardingStatus status,
        OnboardingRequestData data,
        List<OnboardingDocument> documents,
        String legalEntityId,
        ReviewDecision decision,
        Replay replay) {

    public static final int MAX_OTHER_DOCUMENTS = 5;
    public static final long MAX_DOCUMENTS_BYTES = 50L * 1024 * 1024;

    public OnboardingRequest {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /**
     * What recognizes a create or a submit sent again, so that it returns this request instead of
     * acting twice.
     *
     * @param createKey   the {@code Idempotency-Key} it was created with; null without one
     * @param createdWith the data it was created with: a create without a key and with the same
     *                    data is the same create
     * @param submitKey   the {@code Idempotency-Key} it was submitted with; null without one, and
     *                    while it is a draft
     */
    public record Replay(String createKey, OnboardingRequestData createdWith, String submitKey) {
    }

    /** A new draft, at version 1 and without any document. */
    public static OnboardingRequest draft(String id, String businessId, String connectorId, OnboardingRequestData data,
                                          String createKey, Instant now) {
        return new OnboardingRequest(id, businessId, connectorId, 1, now, now, null, OnboardingStatus.DRAFT, data,
                List.of(), null, null, new Replay(createKey, data, null));
    }

    /** Refuses a change that names another version than the current one: the caller read it before a change. */
    public void requireVersion(long expected) {
        if (expected != version) {
            throw new ProblemException(ProblemCode.VERSION_CONFLICT,
                    "If-Match \"%d\" is not the request's current version \"%d\": read it again, then retry"
                            .formatted(expected, version));
        }
    }

    public boolean isDraft() {
        return status == OnboardingStatus.DRAFT;
    }

    // --- the applicant's changes to the draft -------------------------------------------------

    /**
     * Replaces the draft's data. The documents stay, but for the signed copies that no longer
     * match it ({@link #staleDocuments}).
     */
    public OnboardingRequest withData(OnboardingRequestData newData, Instant at) {
        requireDraft();
        var stale = staleDocuments(newData);
        return draftChange(newData, documents.stream().filter(document -> !stale.contains(document)).toList(), at);
    }

    /**
     * The signed copies the given data would no longer match: the GTC when the GTC version
     * changes, and a use case agreement when its use case leaves {@code ucas} or its version
     * changes.
     */
    public List<OnboardingDocument> staleDocuments(OnboardingRequestData newData) {
        return documents.stream().filter(document -> switch (document.type()) {
            case GTC -> !Objects.equals(data.gtcVersionNumber(), newData.gtcVersionNumber());
            case UCA -> {
                var before = data.uca(document.useCaseId()).map(UseCaseAgreement::versionNumber).orElse(null);
                var after = newData.uca(document.useCaseId());
                yield after.isEmpty() || !Objects.equals(before, after.get().versionNumber());
            }
            default -> false;
        }).toList();
    }

    /**
     * Adds a document under the given id. Refused when its use case is not one of the request's,
     * when a power of attorney comes before its expiry date, when its slot already holds one, past
     * 5 {@code other} documents, and past 50 MB of documents.
     */
    public OnboardingRequest withDocument(UUID documentId, DocumentUpload upload, Instant at) {
        requireDraft();
        var type = upload.type();
        if (type == DocumentType.UCA && data.uca(upload.useCaseId()).isEmpty()) {
            throw new ProblemException(ProblemCode.USE_CASE_NOT_IN_REQUEST,
                    "useCaseId: '%s' is not one of the request's ucas".formatted(upload.useCaseId().id()));
        }
        var validUntil = type == DocumentType.POWER_OF_ATTORNEY ? powerOfAttorneyValidUntil() : null;
        var taken = documents.stream().filter(document -> document.holdsSlot(type, upload.useCaseId())).findFirst();
        if (taken.isPresent()) {
            throw new ProblemException(ProblemCode.DOCUMENT_SLOT_TAKEN,
                    "The request already has its %s: replace its file instead".formatted(slotName(type, upload.useCaseId())),
                    List.of(), taken.get().documentId());
        }
        if (type == DocumentType.OTHER
                && documents.stream().filter(document -> document.type() == DocumentType.OTHER).count()
                >= MAX_OTHER_DOCUMENTS) {
            throw new ProblemException(ProblemCode.OTHER_DOCUMENT_LIMIT_REACHED,
                    "The request already has %d other documents".formatted(MAX_OTHER_DOCUMENTS));
        }
        requireWithinQuota(documentsBytes() + upload.file().size());
        var file = upload.file();
        var document = new OnboardingDocument(documentId, type, upload.useCaseId(), upload.label(), file.filename(),
                file.mediaType(), file.size(), at, validUntil);
        var withDocument = new ArrayList<>(documents);
        withDocument.add(document);
        return draftChange(data, withDocument, at);
    }

    /**
     * Replaces the file of one of the draft's documents, and an {@code other}'s label with it. The
     * document keeps its id, type and use case; the new file counts toward the 50 MB instead of
     * the old one.
     */
    public OnboardingRequest withReplacedFile(UUID documentId, String label, UploadedFile file, Instant at) {
        requireDraft();
        var existing = document(documentId).orElseThrow(() -> unknownDocument(documentId));
        DocumentUpload.checkLabel(existing.type(), label);
        file.check();
        var validUntil = existing.type() == DocumentType.POWER_OF_ATTORNEY ? powerOfAttorneyValidUntil() : null;
        requireWithinQuota(documentsBytes() - existing.sizeBytes() + file.size());
        var replaced = new OnboardingDocument(documentId, existing.type(), existing.useCaseId(),
                existing.type() == DocumentType.OTHER ? label : null, file.filename(), file.mediaType(), file.size(), at,
                validUntil);
        return draftChange(data, documents.stream()
                .map(document -> document.documentId().equals(documentId) ? replaced : document)
                .toList(), at);
    }

    public OnboardingRequest withoutDocument(UUID documentId, Instant at) {
        requireDraft();
        if (document(documentId).isEmpty()) {
            throw unknownDocument(documentId);
        }
        return draftChange(data, documents.stream()
                .filter(document -> !document.documentId().equals(documentId))
                .toList(), at);
    }

    /** Submitted for review: from now on it belongs to the review. What makes a draft complete is {@link SubmitCheck}'s. */
    public OnboardingRequest submitted(Instant at, String submitKey) {
        requireDraft();
        return new OnboardingRequest(id, businessId, connectorId, version + 1, createdAt, at, at,
                OnboardingStatus.SUBMITTED, data, documents, legalEntityId, decision,
                new Replay(replay.createKey(), replay.createdWith(), submitKey));
    }

    // --- the review's decision ----------------------------------------------------------------

    /**
     * Approved, and being provisioned under the given DECADE-X-ID: the participant is registered
     * as a credential holder and offered its credentials. Not decided until that completed.
     */
    public OnboardingRequest approvalInProgress(String decadeXId) {
        return reviewed(OnboardingStatus.APPROVAL_IN_PROGRESS, decadeXId, null);
    }

    /** Approved and provisioned: the participant holds its DECADE-X-ID and was offered its credentials. */
    public OnboardingRequest approved(Instant decidedAt) {
        return reviewed(OnboardingStatus.APPROVED, legalEntityId, new ReviewDecision(decidedAt, null, null, null));
    }

    /**
     * Approved, but its provisioning failed. Nothing retries it — there is no TSP operator — so it
     * is final; {@code rejectComment} says what failed, as the decision is the only place the
     * request's view carries an explanation.
     */
    public OnboardingRequest approvalFailed(Instant decidedAt, String reason) {
        return reviewed(OnboardingStatus.APPROVAL_FAILED, legalEntityId, new ReviewDecision(decidedAt, null, reason, null));
    }

    /** Rejected for the given reason. */
    public OnboardingRequest rejected(Instant decidedAt, ReviewDecision.RejectReasonCode code, String comment,
                                      boolean resubmissionAllowed) {
        return reviewed(OnboardingStatus.REJECTED, legalEntityId,
                new ReviewDecision(decidedAt, code, comment, resubmissionAllowed));
    }

    /** Whether this request holds its DECADE-X-ID: approved, or being provisioned as such. */
    public boolean holdsLegalEntityId() {
        return legalEntityId != null
                && (status == OnboardingStatus.APPROVAL_IN_PROGRESS || status == OnboardingStatus.APPROVED);
    }

    // --- documents ----------------------------------------------------------------------------

    public Optional<OnboardingDocument> document(UUID documentId) {
        return documents.stream().filter(document -> document.documentId().equals(documentId)).findFirst();
    }

    /** The document in the slot of the given type — of the given use case, for a {@code UCA}. */
    public Optional<OnboardingDocument> document(DocumentType type, UseCase useCase) {
        return documents.stream().filter(document -> document.holdsSlot(type, useCase)).findFirst();
    }

    public List<OnboardingDocument> documents(DocumentType type) {
        return documents.stream().filter(document -> document.type() == type).toList();
    }

    /** What the documents weigh together. */
    public long documentsBytes() {
        return documents.stream().mapToLong(OnboardingDocument::sizeBytes).sum();
    }

    /** Refuses a change of a request that is no longer a draft: it belongs to the review. */
    public void requireDraft() {
        if (!isDraft()) {
            throw new ProblemException(ProblemCode.INVALID_ONBOARDING_REQUEST_STATE,
                    "The request has been submitted (%s) and can no longer be changed".formatted(status));
        }
    }

    private void requireWithinQuota(long bytes) {
        if (bytes > MAX_DOCUMENTS_BYTES) {
            throw new ProblemException(ProblemCode.DOCUMENT_QUOTA_EXCEEDED,
                    "The documents of the request would weigh more than 50 MB together");
        }
    }

    /** A power of attorney records when it expires, so the date must be given first. */
    private LocalDate powerOfAttorneyValidUntil() {
        var validUntil = data.legalPerson() == null ? null : data.legalPerson().powerOfAttorneyValidUntil();
        if (validUntil == null) {
            throw ProblemException.invalidUpload("legalPerson.powerOfAttorneyValidUntil: required before a "
                    + "power-of-attorney is added");
        }
        return validUntil;
    }

    private OnboardingRequest draftChange(OnboardingRequestData newData, List<OnboardingDocument> newDocuments,
                                          Instant at) {
        return new OnboardingRequest(id, businessId, connectorId, version + 1, createdAt, at, submittedAt, status,
                newData, newDocuments, legalEntityId, decision, replay);
    }

    private OnboardingRequest reviewed(OnboardingStatus newStatus, String newLegalEntityId, ReviewDecision newDecision) {
        return new OnboardingRequest(id, businessId, connectorId, version + 1, createdAt, updatedAt, submittedAt,
                newStatus, data, documents, newLegalEntityId, newDecision, replay);
    }

    private static ProblemException unknownDocument(UUID documentId) {
        return ProblemException.notFound("No document " + documentId);
    }

    private static String slotName(DocumentType type, UseCase useCase) {
        return type == DocumentType.UCA ? "uca for '%s'".formatted(useCase.id()) : type.wireName();
    }
}
