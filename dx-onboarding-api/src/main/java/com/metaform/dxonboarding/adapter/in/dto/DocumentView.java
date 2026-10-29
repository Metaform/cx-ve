package com.metaform.dxonboarding.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentType;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingDocument;
import com.metaform.dxonboarding.domain.model.onboarding.UseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A document of a request ({@code OnboardingDocumentDto}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentView(
        UUID documentId,
        DocumentType type,
        UseCase useCaseId,
        String label,
        String filename,
        String contentType,
        long sizeBytes,
        Instant uploadedAt,
        LocalDate validUntil) {

    public static DocumentView from(OnboardingDocument document) {
        return new DocumentView(document.documentId(), document.type(), document.useCaseId(), document.label(),
                document.filename(), document.contentType(), document.sizeBytes(), document.uploadedAt(),
                document.validUntil());
    }
}
