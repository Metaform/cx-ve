package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.domain.InvalidOnboardingRequestException;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.Submission;
import java.util.NoSuchElementException;

/**
 * The TSP's onboarding intake, as a dataspace participant reaches it through the federated
 * connector. Every call is a connector's, identified by what the connector's data plane stamped
 * on it; requests are scoped to the connector that submitted them.
 */
public interface OnboardingRequestService {

    /**
     * Creates and submits an onboarding request in one step; it then awaits review by a TSP
     * operator. Sending the same content again returns the original request instead of creating a
     * second one.
     *
     * @throws InvalidOnboardingRequestException when the submission is incomplete; nothing is stored
     */
    OnboardingRequest submit(String connectorId, Submission submission);

    /**
     * @throws NoSuchElementException for an unknown request, and for one another connector
     *                                submitted — the two are indistinguishable
     */
    OnboardingRequest get(String connectorId, String requestId);
}
