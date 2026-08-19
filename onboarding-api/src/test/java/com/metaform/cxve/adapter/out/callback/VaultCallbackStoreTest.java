package com.metaform.cxve.adapter.out.callback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.adapter.out.vault.VaultAccessException;
import com.metaform.cxve.adapter.out.vault.VaultClient;
import com.metaform.cxve.domain.model.CallbackRequestData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the store against a fake Vault (the JDK's built-in server, the repo's test idiom):
 * the jwtlet-exchange + JWT-login + KV v2 protocol is exactly what the fake speaks, so these
 * tests pin the wire shapes the real dev Vault expects.
 */
class VaultCallbackStoreTest {

    private static final String SECRET_PATH = "participants/data/onboarding-api/callbacks";

    private final ObjectMapper mapper = new ObjectMapper();

    /** KV storage of the fake: raw (encoded) request path -> the secret's data map. */
    private final Map<String, Map<String, Object>> secrets = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> loginBodies = new ArrayList<>();
    private final List<String> exchangeRequests = new ArrayList<>();
    private final AtomicInteger forbiddenBudget = new AtomicInteger();
    private final AtomicInteger serverErrorBudget = new AtomicInteger();

    private HttpServer vault;
    private VaultCallbackStore store;

    @BeforeEach
    void startFakeVault() throws IOException {
        vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vault.createContext("/v1/auth/jwt/login", exchange -> {
            loginBodies.add(readJson(exchange));
            respond(exchange, 200, Map.of("auth", Map.of(
                    "client_token", "vault-token-" + loginBodies.size(),
                    "lease_duration", 3600)));
        });
        vault.createContext("/v1/" + SECRET_PATH, exchange -> {
            if (forbiddenBudget.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(exchange, 403, Map.of("errors", List.of("permission denied")));
                return;
            }
            if (serverErrorBudget.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(exchange, 500, Map.of("errors", List.of("internal error")));
                return;
            }
            // Raw path: URL-encoding of the client id must be observable, not undone by URI.getPath()
            var path = exchange.getRequestURI().getRawPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                var data = secrets.get(path);
                if (data == null) {
                    respond(exchange, 404, Map.of("errors", List.of()));
                } else {
                    respond(exchange, 200, Map.of("data", Map.of("data", data)));
                }
            } else {
                var body = readJson(exchange);
                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) body.get("data");
                secrets.put(path, data);
                respond(exchange, 200, Map.of("data", Map.of()));
            }
        });
        vault.start();

        var vaultUrl = "http://127.0.0.1:" + vault.getAddress().getPort();
        var client = new VaultClient((resource, scopes) -> {
            exchangeRequests.add(resource + "|" + scopes);
            return "exchanged-jwt";
        }, vaultUrl, "participant", "onboarding-api", "read");
        store = new VaultCallbackStore(client, SECRET_PATH);
    }

    @AfterEach
    void stopFakeVault() {
        vault.stop(0);
    }

    @Test
    void putThenGet_roundTripsAllFields_includingNulls() {
        // The e2e suite registers a bare callbackUrl — three null fields must survive the KV
        // round trip as nulls, not vanish or become empty strings.
        store.put("client-1", new CallbackRequestData("https://osp.example/status", null, null, null));
        store.put("client-2", new CallbackRequestData("https://two.example/status",
                "https://auth.example/token", "osp-oauth-client", "s3cret"));

        assertThat(store.get("client-1"))
                .isEqualTo(new CallbackRequestData("https://osp.example/status", null, null, null));
        assertThat(store.get("client-2")).isEqualTo(new CallbackRequestData("https://two.example/status",
                "https://auth.example/token", "osp-oauth-client", "s3cret"));
        assertThat(secrets).containsKey("/v1/" + SECRET_PATH + "/client-1");
    }

    @Test
    void get_unknownClient_returnsNull() {
        assertThat(store.get("nobody")).isNull();
    }

    @Test
    void authentication_replicatesThePlatformsNonRootPath() {
        // The exchange must select this app's vault partition (jwtlet resource) and the login
        // must use the platform's participant role with the EXCHANGED token — never a static
        // or root token.
        store.put("client-1", new CallbackRequestData("https://osp.example/status", null, null, null));

        assertThat(exchangeRequests).containsExactly("onboarding-api|read");
        assertThat(loginBodies).hasSize(1);
        assertThat(loginBodies.get(0)).containsEntry("role", "participant").containsEntry("jwt", "exchanged-jwt");
    }

    @Test
    void theClientToken_isCachedAcrossOperations() {
        // One login serves many operations: the token is cached to 80% of its lease, so three
        // operations must not mean three exchanges and three logins.
        store.put("client-1", new CallbackRequestData("https://osp.example/status", null, null, null));
        store.get("client-1");
        store.get("nobody");

        assertThat(loginBodies).hasSize(1);
        assertThat(exchangeRequests).hasSize(1);
    }

    @Test
    void aRevokedToken_isReplacedByAFreshLogin() {
        // Dev-mode Vault forgets client tokens on restart; the store must recover from a 403 by
        // logging in again, invisibly to the caller.
        store.put("client-1", new CallbackRequestData("https://osp.example/status", null, null, null));
        forbiddenBudget.set(1);

        assertThat(store.get("client-1")).isNotNull();
        assertThat(loginBodies).hasSize(2);
    }

    @Test
    void clientIds_areUrlEncodedIntoThePath() {
        // The client identity is a token subject — it may carry path-hostile characters (an SA
        // identity like system:serviceaccount:... or worse) and must never remodel the path.
        store.put("acme corp/client:1", new CallbackRequestData("https://osp.example/status", null, null, null));

        assertThat(secrets).containsKey("/v1/" + SECRET_PATH + "/acme+corp%2Fclient%3A1");
    }

    @Test
    void aVaultFailure_propagates() {
        // A registration that could not be stored (or read back) must surface to the caller —
        // a 204 for a callback that evaporated would be a silent contract breach.
        serverErrorBudget.set(1);
        assertThatThrownBy(() -> store.put("client-1",
                new CallbackRequestData("https://osp.example/status", null, null, null)))
                .isInstanceOf(VaultAccessException.class);

        serverErrorBudget.set(1);
        assertThatThrownBy(() -> store.get("client-1")).isInstanceOf(VaultAccessException.class);
    }

    private Map<String, Object> readJson(HttpExchange exchange) {
        try (var body = exchange.getRequestBody()) {
            return mapper.readValue(body, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        var bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
