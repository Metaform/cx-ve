package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one callback registration per onboarding service provider, keyed on the client identity
 * it registered under — so a second provider setting its callback no longer overwrites the first.
 * The key is the caller's authenticated identity, handed in by the controller; it is never taken
 * from the payload. Status updates fan out to every registered callback: an onboarding process
 * carries no record of which client submitted it, so all providers are notified and filter for
 * their own registrations.
 */
@Service
public class InMemoryRegistrationStatusService implements RegistrationStatusService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRegistrationStatusService.class);

    private final Map<String, CallbackRequestData> callbacks = new ConcurrentHashMap<>();

    @Override
    public CallbackRequestData getCallbackAddress(String clientId) {
        return callbacks.get(clientId);
    }

    @Override
    public void setCallbackAddress(String clientId, CallbackRequestData callbackData) {
        callbacks.put(clientId, callbackData);
    }

    @Override
    public void invokeCallback(OnboardingProcess after) {
        // fire and forget, each callback on its own: one unreachable provider must not keep the
        // remaining ones from being notified
        callbacks.forEach((clientId, callback) -> {
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
                log.warn("Error invoking callback for client '{}'", clientId, e);
            }
        });
    }
}
