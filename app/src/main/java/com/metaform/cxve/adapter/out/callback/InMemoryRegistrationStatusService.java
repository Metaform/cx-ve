package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.OnboardingServiceProviderCallbackRequestData;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class InMemoryRegistrationStatusService implements RegistrationStatusService {

    private static OnboardingServiceProviderCallbackRequestData callback;

    public InMemoryRegistrationStatusService() {
    }

    @Override
    public OnboardingServiceProviderCallbackRequestData getCallbackAddress() {
        return callback;
    }

    @Override
    public void setCallbackAddress(OnboardingServiceProviderCallbackRequestData callbackData) {
        callback = callbackData;
    }

    @Override
    public void invokeCallback() {
        if (callback != null) {
            // fire and forget
            var response = RestClient.builder()
                    .baseUrl(callback.callbackUrl())
                    .build()
                    .post()
                    .retrieve()
                    .toBodilessEntity();
        }
    }
}
