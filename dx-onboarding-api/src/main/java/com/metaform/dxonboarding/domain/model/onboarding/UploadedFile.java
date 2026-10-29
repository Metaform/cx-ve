package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.ProblemException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * A document's file as uploaded: its name, the content type of its part, and its bytes.
 *
 * @param contentType the part's {@code Content-Type}, which must say what the file is
 */
public record UploadedFile(String filename, String contentType, byte[] content) {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_FILENAME_LENGTH = 255;

    /** The media types accepted, with the bytes a file of each starts with. */
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", "%PDF-".getBytes(StandardCharsets.US_ASCII),
            "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'},
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    public long size() {
        return content == null ? 0 : content.length;
    }

    /** The bare media type of {@link #contentType()}: no parameters, lower case. */
    public String mediaType() {
        if (contentType == null) {
            return "";
        }
        var semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a file the TSP does not take, with the first problem found: none or an empty one, no
     * file name or a too long one (400), not a PDF, PNG or JPEG (415), more than 10 MB (413), or
     * bytes that are not what the content type says (400).
     */
    public void check() {
        if (content == null) {
            throw ProblemException.invalidUpload("file: required");
        }
        if (content.length == 0) {
            throw ProblemException.invalidUpload("file: empty");
        }
        if (filename == null || filename.isBlank()) {
            throw ProblemException.invalidUpload("file: a file name is required");
        }
        if (filename.length() > MAX_FILENAME_LENGTH) {
            throw ProblemException.invalidUpload("file: the file name is longer than 255 characters");
        }
        var signature = SIGNATURES.get(mediaType());
        if (signature == null) {
            throw new ProblemException(ProblemCode.UNSUPPORTED_DOCUMENT_TYPE,
                    "file: '%s' is not a PDF, PNG or JPEG".formatted(contentType));
        }
        if (content.length > MAX_BYTES) {
            throw new ProblemException(ProblemCode.CONTENT_TOO_LARGE, "file: larger than 10 MB");
        }
        if (content.length < signature.length
                || !Arrays.equals(Arrays.copyOf(content, signature.length), signature)) {
            throw ProblemException.invalidUpload("file: the content is not what its Content-Type '%s' says"
                    .formatted(mediaType()));
        }
    }
}
