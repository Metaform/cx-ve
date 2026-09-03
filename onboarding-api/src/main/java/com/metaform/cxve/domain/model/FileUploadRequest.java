package com.metaform.cxve.domain.model;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for file upload announcements (the payload of
 * POST /api/administration/registration/network/partnerregistration/fileupload). Both components
 * are mandatory; they are enforced at the web boundary via {@code @Valid} on the controller.
 */
public record FileUploadRequest(
        @NotBlank String fileName,
        @NotBlank String contentType
) {
}
