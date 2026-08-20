package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OspRegistrationCallbackData;
import com.metaform.cxve.domain.model.RegistrationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Holds one callback registration per onboarding service provider, keyed on the client identity
 * it registered under — so a second provider setting its callback no longer overwrites the first.
 * The key is the caller's authenticated identity, handed in by the controller; it is never taken
 * from the payload. Status updates are routed to the callback of the client that submitted the
 * process ({@link OnboardingProcess#clientId()}); a process without a recorded submitter is
 * dropped with a warning — no provider gets to see a registration that is not its own.
 *
 * <p>Storage lives behind {@link CallbackStore}: Vault by default (the registration carries the
 * provider's OAuth2 client secret), in-memory under the "test" profile.
 */
@Service
public class DefaultRegistrationStatusService implements RegistrationStatusService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRegistrationStatusService.class);

    private final CallbackStore callbacks;

    public DefaultRegistrationStatusService(CallbackStore callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public CallbackRequestData getCallbackAddress(String clientId) {
        return callbacks.get(clientId);
    }

    @Override
    public void setCallbackAddress(String clientId, CallbackRequestData callbackData) {
        log.debug("Setting callback address for client ID '{}' to URL '{}'", clientId, callbackData.callbackUrl());
        callbacks.put(clientId, callbackData);
    }

    @Override
    public void invokeCallback(OnboardingProcess after) {
        if (after.clientId() == null) {
            log.warn("No client-ID registered for onboarding {}, dropping the status update", after.id());
            return;
        }
        // The process knows its submitter: only that provider's callback is notified — other
        // providers have no business seeing this registration's outcome. The store read shares
        // the fire-and-forget contract of the call itself: an unreachable store must not fail
        // the onboarding whose outcome is being announced.
        CallbackRequestData callback;
        try {
            callback = callbacks.get(after.clientId());
        } catch (Exception e) {
            log.warn("Could not load the callback of client '{}', dropping the status update for onboarding {}",
                    after.clientId(), after.id(), e);
            return;
        }
        if (callback == null) {
            log.warn("No callback registered for client '{}', dropping the status update for onboarding {}",
                    after.clientId(), after.id());
            return;
        }
        var regData = new OspRegistrationCallbackData(after.externalId(), RegistrationStatus.from(after.state()), after.failureReason());
        post(after.clientId(), callback, regData);
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
