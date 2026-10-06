package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.adapter.out.vault.VaultClient;
import com.metaform.cxve.domain.model.CallbackRequestData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The default callback store: registrations live in the platform's Vault, one KV v2 secret per
 * client under this app's vault partition ({@code participants/data/<partition>/callbacks/...}),
 * because a registration carries the provider's OAuth2 client secret. Writes and reads go
 * through {@link VaultClient}, i.e. with a partition-confined token, never a root token.
 */
@Component
@Profile("!test")
public class VaultCallbackStore implements CallbackStore {

    private final VaultClient vault;
    private final String secretPath;

    public VaultCallbackStore(VaultClient vault, @Value("${vault.secret-path}") String secretPath) {
        this.vault = vault;
        this.secretPath = secretPath.endsWith("/") ? secretPath.substring(0, secretPath.length() - 1) : secretPath;
    }

    @Override
    public CallbackRequestData get(String clientId) {
        var data = vault.readKv(pathFor(clientId));
        if (data == null) {
            return null;
        }
        return new CallbackRequestData(
                (String) data.get("callbackUrl"),
                (String) data.get("authUrl"),
                (String) data.get("clientId"),
                (String) data.get("clientSecret"));
    }

    @Override
    public void put(String clientId, CallbackRequestData callbackData) {
        // HashMap, not Map.of: every field may legitimately be null (the e2e suite registers a
        // bare callbackUrl), and the KV payload must round-trip them as nulls.
        var data = new HashMap<String, Object>();
        data.put("callbackUrl", callbackData.callbackUrl());
        data.put("authUrl", callbackData.authUrl());
        data.put("clientId", callbackData.clientId());
        data.put("clientSecret", callbackData.clientSecret());
        vault.writeKv(pathFor(clientId), data);
    }

    /**
     * The client identity is the caller's token subject — free-form from Vault's point of view —
     * so it is URL-encoded into the path rather than trusted as a path segment.
     */
    private String pathFor(String clientId) {
        return secretPath + "/" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
    }
}
