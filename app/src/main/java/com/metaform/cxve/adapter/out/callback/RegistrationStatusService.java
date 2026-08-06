package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.OnboardingProcess;
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

    void invokeCallback(OnboardingProcess after);
}
