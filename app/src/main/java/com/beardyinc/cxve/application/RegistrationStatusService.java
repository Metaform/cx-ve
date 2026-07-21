package com.beardyinc.cxve.application;

import com.beardyinc.cxve.domain.model.OnboardingServiceProviderCallbackRequestData;

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
