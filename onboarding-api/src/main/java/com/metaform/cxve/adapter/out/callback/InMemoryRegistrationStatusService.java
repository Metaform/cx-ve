package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OspRegistrationCallbackData;
import com.metaform.cxve.domain.model.RegistrationStatus;
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
 * from the payload. Status updates are routed to the callback of the client that submitted the
 * process ({@link OnboardingProcess#clientId()}); a process without a recorded submitter is
 * dropped with a warning — no provider gets to see a registration that is not its own.
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
        if (after.clientId() != null) {
            // The process knows its submitter: only that provider's callback is notified — other
            // providers have no business seeing this registration's outcome.
            var callback = callbacks.get(after.clientId());
            if (callback == null) {
                log.warn("No callback registered for client '{}', dropping the status update for onboarding {}",
                        after.clientId(), after.id());
                return;
            }
            var regData = new OspRegistrationCallbackData(after.externalId(), RegistrationStatus.from(after.state()), after.failureReason());
            post(after.clientId(),callback, regData);
        } else {
            log.warn("No client-ID registered for onboarding {}, dropping the status update", after.id());
        }
    }

    /** Fire and forget: an unreachable provider is logged, never propagated. */
    private void post(String clientId, CallbackRequestData callback, OspRegistrationCallbackData callbackData) {
        try {
            //todo: potentially use an injected, managed/pooled builder?
            RestClient.builder()
                    .baseUrl(callback.callbackUrl())
                    .build()
                    .post()
                    .body(callbackData)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Error invoking callback for client '{}'", clientId, e);
        }
    }
}
