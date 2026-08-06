package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingServiceProviderCallbackRequestData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class InMemoryRegistrationStatusService implements RegistrationStatusService {

    private static OnboardingServiceProviderCallbackRequestData callback;
    private static final Logger log = LoggerFactory.getLogger(InMemoryRegistrationStatusService.class);

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
    public void invokeCallback(OnboardingProcess after) {
        if (callback != null) {
            // fire and forget
            try {
                //todo: potentially use an injected, managed/pooled builder?
                RestClient.builder()
                        .baseUrl(callback.callbackUrl())
                        .build()
                        .post()
                        .body(after)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Error invoking callback", e);
            }
        }
    }
}
