package com.beardyinc.cxve.service;

import com.beardyinc.cxve.model.OnboardingServiceProviderCallbackRequestData;

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
