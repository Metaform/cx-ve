package com.metaform.dxonboarding.adapter.out.document;

import com.metaform.dxonboarding.domain.model.onboarding.DocumentRef;
import com.metaform.dxonboarding.domain.model.onboarding.SubmittedDocument;
import com.metaform.dxonboarding.domain.port.DocumentStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In memory only: a restart forgets every document, which is fine for the VE. */
@Component
public class InMemoryDocumentStore implements DocumentStore {

    private final Map<UUID, byte[]> contents = new ConcurrentHashMap<>();

    @Override
    public DocumentRef store(SubmittedDocument document) {
        var ref = new DocumentRef(UUID.randomUUID(), document.filename(), document.contentType(), document.size(),
                Instant.now());
        contents.put(ref.documentId(), document.content().clone());
        return ref;
    }
}
