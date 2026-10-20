package com.metaform.dxonboarding.adapter.in.dto;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;

/** A request's new version, after a document was removed ({@code OnboardingRequestVersionDto}). */
public record VersionView(String version) {

    public static VersionView from(OnboardingRequest request) {
        return new VersionView(String.valueOf(request.version()));
    }
}
