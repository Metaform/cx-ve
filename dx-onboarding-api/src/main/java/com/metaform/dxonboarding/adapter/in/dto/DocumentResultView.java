package com.metaform.dxonboarding.adapter.in.dto;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingDocument;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;

/** A document added or replaced, with the request's new version ({@code OnboardingDocumentResultDto}). */
public record DocumentResultView(String version, DocumentView document) {

    public static DocumentResultView from(OnboardingRequest request, OnboardingDocument document) {
        return new DocumentResultView(String.valueOf(request.version()), DocumentView.from(document));
    }
}
