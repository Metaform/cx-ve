package com.metaform.cxve.domain.model;

/**
 * File submitted along the {@link PartnerRegistrationData}. Jackson maps {@code byte[]} to/from a
 * base64-encoded string, matching the API contract.
 */
public record DocumentUpload(
        DocumentTypeId documentType,
        String fileName,
        byte[] fileContent
) {
}
