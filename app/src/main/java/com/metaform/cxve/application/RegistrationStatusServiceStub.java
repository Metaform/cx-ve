package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.OnboardingServiceProviderCallbackRequestData;
import org.springframework.stereotype.Service;

@Service
public class RegistrationStatusServiceStub implements RegistrationStatusService {

    @Override
    public OnboardingServiceProviderCallbackRequestData getCallbackAddress() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void setCallbackAddress(OnboardingServiceProviderCallbackRequestData callbackData) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
