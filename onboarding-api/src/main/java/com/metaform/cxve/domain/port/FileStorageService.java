package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.FileUploadResponse;

/**
 * Hands out presigned upload URLs for files submitted alongside a partner registration. The file
 * contents never pass through this application: the provider announces a file here, then PUTs it
 * directly to the returned URL and references it by its id in the registration payload.
 */
public interface FileStorageService {

    /**
     * Assigns an id for the announced file and creates the presigned URL its contents can be
     * uploaded to.
     */
    FileUploadResponse initiateUpload(String fileName, String contentType);
}
