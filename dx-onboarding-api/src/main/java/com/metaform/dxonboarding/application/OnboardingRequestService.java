package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.domain.ProblemException;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentUpload;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingDocument;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.UploadedFile;
import java.util.UUID;

/**
 * The TSP's onboarding intake, as a dataspace participant reaches it through the dataspace: a
 * draft is created, completed with its documents, and submitted for review. Every call is a
 * connector's, identified by what the dataspace stamped on it; requests are scoped to the
 * connector that created them, and another connector's request reads like an unknown one.
 *
 * <p>Every change names the version of the request it was made on ({@code version}, the
 * {@code If-Match} of the call), and every refusal is a {@link ProblemException}, which changes
 * nothing.
 */
public interface OnboardingRequestService {

    /** A create's outcome: the request, and whether the call replayed an earlier create instead of creating it. */
    record Created(OnboardingRequest request, boolean replayed) {
    }

    /** A document added or replaced: the request at its new version, and the document. */
    record DocumentChange(OnboardingRequest request, OnboardingDocument document) {
    }

    /** A document's file, with what it is. */
    record DocumentFile(OnboardingDocument document, byte[] content) {
    }

    /**
     * Creates a draft. A create sent again — with the same {@code idempotencyKey}, or without one
     * and with the same data — returns the request the first one created, whatever became of it.
     *
     * @param idempotencyKey optional; a key already used with different data is refused
     */
    Created create(String connectorId, OnboardingRequestData data, String idempotencyKey);

    OnboardingRequest get(String connectorId, String requestId);

    /** Replaces the draft's data, dropping the signed documents that no longer match it. */
    OnboardingRequest replace(String connectorId, String requestId, long version, OnboardingRequestData data);

    /** Deletes the draft and its documents, for good. */
    void delete(String connectorId, String requestId, long version);

    DocumentChange addDocument(String connectorId, String requestId, long version, DocumentUpload upload);

    /** Replaces the file of a document of the draft (and an {@code other}'s label); the document keeps its id. */
    DocumentChange replaceDocument(String connectorId, String requestId, UUID documentId, long version, String label,
                                   UploadedFile file);

    /** Removes a document from the draft; returns the request at its new version. */
    OnboardingRequest removeDocument(String connectorId, String requestId, UUID documentId, long version);

    /** A document's file, whatever the request's status. */
    DocumentFile download(String connectorId, String requestId, UUID documentId);

    /**
     * Submits the draft for review, once it is complete and its DID is free and resolves. A
     * request already submitted is returned as it is.
     *
     * @param idempotencyKey optional; a key already used to submit another request is refused
     */
    OnboardingRequest submit(String connectorId, String requestId, long version, String idempotencyKey);
}
