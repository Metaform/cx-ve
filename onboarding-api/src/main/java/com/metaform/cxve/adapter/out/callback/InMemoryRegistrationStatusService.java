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
 * Holds one callback registration per onboarding service provider, keyed on the client id it
 * registered under — so a second provider setting its callback no longer overwrites the first.
 * Status updates fan out to every registered callback: an onboarding process carries no record of
 * which client submitted it, so all providers are notified and filter for their own registrations.
 */
@Service
public class InMemoryRegistrationStatusService implements RegistrationStatusService {

    /**
     * Map slot for a registration without a client id. The map rejects null keys, and a provider
     * that does not identify itself still deserves the pre-multi-client behavior: one anonymous
     * registration, overwritten by the next anonymous one.
     */
    private static final String ANONYMOUS_CLIENT = "";

    private static final Logger log = LoggerFactory.getLogger(InMemoryRegistrationStatusService.class);

    private final Map<String, CallbackRequestData> callbacks = new ConcurrentHashMap<>();

    @Override
    public CallbackRequestData getCallbackAddress(String clientId) {
        return callbacks.get(clientKey(clientId));
    }

    @Override
    public void setCallbackAddress(CallbackRequestData callbackData) {
        callbacks.put(clientKey(callbackData.clientId()), callbackData);
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

    private String clientKey(String clientId) {
        return clientId == null ? ANONYMOUS_CLIENT : clientId;
    }
}
