package com.metaform.dxonboarding.domain.model.onboarding;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A document part of a submission, as received.
 *
 * @param part        the multipart part it arrived in, which says what it is — e.g. {@code gtcDocument}
 *                    or {@code ucaDocument[<useCaseId>]}
 * @param contentType the part's declared content type, which the format is taken from
 */
public record SubmittedDocument(String part, String filename, String contentType, byte[] content) {

    public long size() {
        return content.length;
    }

    /** The SHA-256 of the content, hex-encoded. */
    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
