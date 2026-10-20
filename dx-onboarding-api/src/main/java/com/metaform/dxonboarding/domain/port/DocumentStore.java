package com.metaform.dxonboarding.domain.port;

import java.util.Optional;
import java.util.UUID;

/** Keeps the files of the requests' documents, by document id. */
public interface DocumentStore {

    /** Stores the file of the document, replacing the one it had. */
    void store(UUID documentId, byte[] content);

    Optional<byte[]> load(UUID documentId);

    /** Deletes the file of the document, for good; nothing happens when there is none. */
    void delete(UUID documentId);
}
