package com.metaform.dxonboarding.adapter.out.document;

import com.metaform.dxonboarding.domain.port.DocumentStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In memory only: a restart forgets every document, which is fine for the VE. */
@Component
public class InMemoryDocumentStore implements DocumentStore {

    private final Map<UUID, byte[]> contents = new ConcurrentHashMap<>();

    @Override
    public void store(UUID documentId, byte[] content) {
        contents.put(documentId, content.clone());
    }

    @Override
    public Optional<byte[]> load(UUID documentId) {
        return Optional.ofNullable(contents.get(documentId)).map(byte[]::clone);
    }

    @Override
    public void delete(UUID documentId) {
        contents.remove(documentId);
    }
}
