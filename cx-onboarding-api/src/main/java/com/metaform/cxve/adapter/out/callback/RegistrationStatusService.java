package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.CallbackRequestData;

public interface RegistrationStatusService {

    /**
     * Gets the callback address registered under the given client identity, or null if none is.
     */
    CallbackRequestData getCallbackAddress(String clientId);

    /**
     * Registers the callback address of an onboarding service provider under the given client
     * identity — the caller's authenticated identity, not anything from the payload (whose
     * clientId is the provider's own OAuth2 client for the outbound callback call). Re-registering
     * under the same identity overwrites that registration, and only that one.
     */
    void setCallbackAddress(String clientId, CallbackRequestData callbackData);

    /**
     * Notifies the callback of the client that submitted the process ({@code process.clientId()})
     * of its status; a process without a recorded submitter, or a submitter without a registered
     * callback, is dropped with a warning.
     */
    void invokeCallback(OnboardingProcess after);
}
