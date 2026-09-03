package com.metaform.cxve.domain.model;

import java.time.Instant;

/**
 * Response to a {@link FileUploadRequest}: the id the file can later be referenced by (the
 * {@code fileIds} of a {@link PartnerRegistrationData}) and the presigned URL the provider
 * uploads the file contents to. {@code expiresAt} is the URL's validity end, null when the URL
 * does not expire.
 */
public record FileUploadResponse(
        String fileId,
        String presignedUploadUrl,
        Instant expiresAt
) {
}
