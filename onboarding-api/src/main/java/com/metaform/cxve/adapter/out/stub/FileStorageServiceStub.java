package com.metaform.cxve.adapter.out.stub;

import com.metaform.cxve.domain.model.FileUploadResponse;
import com.metaform.cxve.domain.port.FileStorageService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placeholder file storage. Assigns a random file id and fabricates an upload URL under a
 * non-resolvable host — a real implementation would presign against an object store (e.g. S3).
 */
@Service
public class FileStorageServiceStub implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageServiceStub.class);
    private static final Duration VALIDITY = Duration.ofHours(1);

    @Override
    public FileUploadResponse initiateUpload(String fileName, String contentType) {
        var fileId = UUID.randomUUID() +"-stub";
        var url = "https://storage.invalid/upload/" + fileId;
        log.debug("Generated placeholder upload URL for file '{}' ({}) as fileId={}", fileName, contentType, fileId);
        return new FileUploadResponse(fileId, url, Instant.now().plus(VALIDITY));
    }
}
