package com.metaform.dxonboarding.domain.port;

import com.metaform.dxonboarding.domain.model.onboarding.DocumentRef;
import com.metaform.dxonboarding.domain.model.onboarding.SubmittedDocument;

/** Keeps the documents of a submission available to the TSP operator who reviews it. */
public interface DocumentStore {

    /** Stores the document and returns the reference it is filed under. */
    DocumentRef store(SubmittedDocument document);
}
