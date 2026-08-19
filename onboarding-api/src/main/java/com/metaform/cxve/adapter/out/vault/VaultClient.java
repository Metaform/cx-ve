package com.metaform.cxve.adapter.out.vault;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaform.cxve.adapter.out.auth.TokenProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Vault access the way the platform components do it for named vault partitions (and the only
 * way that does not involve the dev root token): the pod's projected service-account token is
 * exchanged at jwtlet (RFC 8693, via the existing {@link TokenProvider}) for a participant token
 * whose {@code sub} is this app's vault partition, and that JWT is logged in at Vault's
 * pre-existing {@code participant} JWT role. The resulting client token is confined by the
 * platform's {@code participants-restricted} policy to {@code participants/data/<sub>/*} —
 * least privilege without any Vault-side seeding.
 *
 * <p>KV v2 only ({@code participants/} is a KV v2 mount): paths passed in are full API paths
 * below {@code /v1/}, e.g. {@code participants/data/onboarding-api/callbacks/<client>}.
 */
@Component
@Profile("!test")
public class VaultClient {

    private final TokenProvider tokenProvider;
    private final RestClient restClient;
    private final String role;
    private final String resource;
    private final String scope;

    private volatile CachedToken cached;

    public VaultClient(@Qualifier("token-exchange") TokenProvider tokenProvider,
                       @Value("${vault.url}") String vaultUrl,
                       @Value("${vault.auth.role}") String role,
                       @Value("${vault.auth.resource}") String resource,
                       @Value("${vault.auth.scope}") String scope) {
        this.tokenProvider = tokenProvider;
        this.restClient = RestClient.builder().baseUrl(vaultUrl).build();
        this.role = role;
        this.resource = resource;
        this.scope = scope;
    }

    /**
     * Reads a KV v2 secret; null when there is none. Retries once through a fresh login on 403 —
     * the cached client token may have been revoked (dev-mode Vault loses everything on restart).
     */
    public Map<String, Object> readKv(String path) {
        try {
            return withAuthRetry(token -> {
                try {
                    var response = restClient.get()
                            .uri("/v1/{path}", path)
                            .header("X-Vault-Token", token)
                            .retrieve()
                            .body(KvReadResponse.class);
                    return Objects.requireNonNull(response).data().data();
                } catch (HttpClientErrorException.NotFound e) {
                    return null;
                }
            });
        } catch (Exception e) {
            throw new VaultAccessException("Failed to read secret at '%s'".formatted(path), e);
        }
    }

    /** Writes a KV v2 secret, replacing the previous version. */
    public void writeKv(String path, Map<String, Object> data) {
        try {
            withAuthRetry(token -> {
                restClient.post()
                        .uri("/v1/{path}", path)
                        .header("X-Vault-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("data", data))
                        .retrieve()
                        .toBodilessEntity();
                return null;
            });
        } catch (Exception e) {
            throw new VaultAccessException("Failed to write secret at '%s'".formatted(path), e);
        }
    }

    private <T> T withAuthRetry(VaultOperation<T> operation) {
        try {
            return operation.execute(clientToken());
        } catch (HttpClientErrorException.Forbidden e) {
            cached = null;
            return operation.execute(clientToken());
        }
    }

    /**
     * The Vault client token, logged in on demand and cached to 80% of its lease so it is
     * replaced before Vault would reject it.
     */
    private synchronized String clientToken() {
        var current = cached;
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return current.value();
        }
        var participantToken = tokenProvider.getToken(resource, scope);
        var response = restClient.post()
                .uri("/v1/auth/jwt/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("role", role, "jwt", participantToken))
                .retrieve()
                .body(LoginResponse.class);
        var auth = Objects.requireNonNull(response).auth();
        cached = new CachedToken(auth.clientToken(),
                Instant.now().plusSeconds(Math.max(1, auth.leaseDuration() * 8 / 10)));
        return cached.value();
    }

    private interface VaultOperation<T> {
        T execute(String clientToken);
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    private record LoginResponse(Auth auth) {
    }

    private record Auth(@JsonProperty("client_token") String clientToken,
                        @JsonProperty("lease_duration") long leaseDuration) {
    }

    private record KvReadResponse(KvData data) {
    }

    private record KvData(Map<String, Object> data) {
    }
}
