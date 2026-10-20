package com.metaform.dxonboarding.adapter.in.dto;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import java.util.List;

/** The documents of a request, oldest first, with its current version ({@code OnboardingDocumentListDto}). */
public record DocumentListView(String version, List<DocumentView> documents) {

    public static DocumentListView from(OnboardingRequest request) {
        return new DocumentListView(String.valueOf(request.version()),
                request.documents().stream().map(DocumentView::from).toList());
    }
}
