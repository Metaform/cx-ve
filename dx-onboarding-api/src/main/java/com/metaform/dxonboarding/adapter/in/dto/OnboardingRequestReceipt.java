package com.metaform.dxonboarding.adapter.in.dto;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import java.time.Instant;

/** Acknowledgement of a submitted onboarding request ({@code DataspaceOnboardingRequestResponseDto}). */
public record OnboardingRequestReceipt(
        String id,
        String businessId,
        OnboardingStatus status,
        String connectorId,
        Instant submittedAt) {

    public static OnboardingRequestReceipt from(OnboardingRequest request) {
        return new OnboardingRequestReceipt(request.id(), request.businessId(), request.status(),
                request.connectorId(), request.submittedAt());
    }
}
