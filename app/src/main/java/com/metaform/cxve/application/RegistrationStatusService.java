package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.OnboardingServiceProviderCallbackRequestData;

public interface RegistrationStatusService {

    /**
     * Gets the callback address of the onboarding service provider.
     */
    OnboardingServiceProviderCallbackRequestData getCallbackAddress();

    /**
     * Sets the callback address of the onboarding service provider.
     */
    void setCallbackAddress(OnboardingServiceProviderCallbackRequestData callbackData);
}
