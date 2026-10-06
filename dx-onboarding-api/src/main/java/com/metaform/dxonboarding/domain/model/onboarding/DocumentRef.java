package com.metaform.dxonboarding.domain.model.onboarding;

import java.time.Instant;
import java.util.UUID;

/** A stored document of a submission, as it is referenced from the request. */
public record DocumentRef(UUID documentId, String filename, String contentType, long sizeBytes, Instant uploadedAt) {
}
