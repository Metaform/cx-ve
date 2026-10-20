package com.metaform.dxonboarding.domain.model.onboarding;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A document of an onboarding request: what it is, and the file on file for it. The file's bytes
 * are kept by the {@link com.metaform.dxonboarding.domain.port.DocumentStore}, under the
 * document's id.
 *
 * @param documentId  kept when the file is replaced
 * @param useCaseId   the use case of a {@code UCA}; null for any other type
 * @param label       what an {@code OTHER} is; null for any other type
 * @param contentType the file's media type: PDF, PNG or JPEG
 * @param uploadedAt  when the current file was uploaded
 * @param validUntil  for a {@code POWER_OF_ATTORNEY}: the {@code legalPerson.powerOfAttorneyValidUntil}
 *                    its file was uploaded with; null for any other type
 */
public record OnboardingDocument(
        UUID documentId,
        DocumentType type,
        UseCase useCaseId,
        String label,
        String filename,
        String contentType,
        long sizeBytes,
        Instant uploadedAt,
        LocalDate validUntil) {

    /** Whether this document holds the slot of the given type and use case. */
    boolean holdsSlot(DocumentType slotType, UseCase useCase) {
        return type != DocumentType.OTHER && type == slotType && (type != DocumentType.UCA || useCaseId == useCase);
    }
}
