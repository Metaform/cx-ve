package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-profile stand-in for {@link VaultCallbackStore}: registrations live in a map and die with
 * the instance. Never the default — callback registrations carry the OSP's client secret.
 */
@Component
@Profile("test")
public class InMemoryCallbackStore implements CallbackStore {

    private final Map<String, CallbackRequestData> callbacks = new ConcurrentHashMap<>();

    @Override
    public CallbackRequestData get(String clientId) {
        return callbacks.get(clientId);
    }

    @Override
    public void put(String clientId, CallbackRequestData callbackData) {
        callbacks.put(clientId, callbackData);
    }
}
