package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.CallbackRequestData;

public interface RegistrationStatusService {

    /**
     * Gets the callback address the onboarding service provider with the given client id has
     * registered, or null if it has not registered one. A null client id addresses the anonymous
     * registration (one set without a client id).
     */
    CallbackRequestData getCallbackAddress(String clientId);

    /**
     * Registers the callback address of an onboarding service provider, keyed on the client id
     * inside the data; a provider re-registering under the same client id overwrites its previous
     * callback, and only that one.
     */
    void setCallbackAddress(CallbackRequestData callbackData);

    /**
     * Notifies every registered callback of the process's status.
     */
    void invokeCallback(OnboardingProcess after);
}
