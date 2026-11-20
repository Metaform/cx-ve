package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;

/**
 * Storage for callback registrations, keyed on the registering client's authenticated identity.
 * The two implementations are gated on complementary profile expressions, so exactly one exists
 * in any context: {@link VaultCallbackStore} by default — the registration carries the OSP's
 * OAuth2 client secret, which belongs in Vault and nowhere else — and {@link InMemoryCallbackStore}
 * under the "test" profile.
 */
public interface CallbackStore {

    /** The registration stored under the given client identity, or null if there is none. */
    CallbackRequestData get(String clientId);

    /** Stores the registration under the given client identity, replacing any previous one. */
    void put(String clientId, CallbackRequestData callbackData);
}
