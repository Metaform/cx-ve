package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultRegistrationStatusServiceTest {

    private final DefaultRegistrationStatusService service = new DefaultRegistrationStatusService(new InMemoryCallbackStore());
    private HttpServer receiver;

    @AfterEach
    void stopReceiver() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    // No authUrl: the callback is invoked plain. The authenticated variant has its own tests —
    // a registration WITH an authUrl whose token endpoint is unreachable drops the update.
    private static CallbackRequestData callback(String url) {
        return new CallbackRequestData(url, null, "osp-oauth-client", "secret");
    }

    /**
     * A real HTTP receiver (the JDK's built-in server) with one counter per path — the only way
     * to observe WHICH registered callback the service actually invoked.
     */
    private HttpServer startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.start();
        return receiver;
    }

    private AtomicInteger countingEndpoint(HttpServer server, String path) {
        var hits = new AtomicInteger();
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        return hits;
    }

    private String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void eachClientKeepsItsOwnCallback() {
        // The reason for the map: a second provider registering must not overwrite the first.
        service.setCallbackAddress("client-1", callback("https://one.example/status"));
        service.setCallbackAddress("client-2", callback("https://two.example/status"));

        assertThat(service.getCallbackAddress("client-1").callbackUrl()).isEqualTo("https://one.example/status");
        assertThat(service.getCallbackAddress("client-2").callbackUrl()).isEqualTo("https://two.example/status");
        assertThat(service.getCallbackAddress("client-3")).isNull();
    }

    @Test
    void reRegistering_overwritesOnlyTheSameClient() {
        service.setCallbackAddress("client-1", callback("https://one.example/status"));
        service.setCallbackAddress("client-2", callback("https://two.example/status"));

        service.setCallbackAddress("client-1", callback("https://one.example/status-v2"));

        assertThat(service.getCallbackAddress("client-1").callbackUrl()).isEqualTo("https://one.example/status-v2");
        assertThat(service.getCallbackAddress("client-2").callbackUrl()).isEqualTo("https://two.example/status");
    }

    @Test
    void theKeyIsTheGivenIdentity_neverThePayloadsClientId() {
        // The payload's clientId ("osp-oauth-client" in the fixture) is the provider's OAuth2
        // client for the outbound callback call — storing under it would let the payload choose
        // whose registration to overwrite.
        service.setCallbackAddress("client-1", callback("https://one.example/status"));

        assertThat(service.getCallbackAddress("client-1")).isNotNull();
        assertThat(service.getCallbackAddress("osp-oauth-client")).isNull();
    }

    @Test
    void instancesDoNotShareState() {
        // The single-callback implementation held its registration in a STATIC field; that must
        // not survive the conversion.
        service.setCallbackAddress("client-1", callback("https://one.example/status"));

        assertThat(new DefaultRegistrationStatusService(new InMemoryCallbackStore()).getCallbackAddress("client-1")).isNull();
    }

    @Test
    void invokeCallback_notifiesOnlyTheSubmittingClient() throws IOException {
        // A registration's outcome is nobody else's business: the process records who submitted
        // it, and only that provider's callback may be called.
        var server = startReceiver();
        var one = countingEndpoint(server, "/one");
        var two = countingEndpoint(server, "/two");
        service.setCallbackAddress("client-1", callback(url(server, "/one")));
        service.setCallbackAddress("client-2", callback(url(server, "/two")));

        service.invokeCallback(OnboardingProcess.submitted("proc-1", "ext-1", null, null, "client-1"));

        assertThat(one.get()).isEqualTo(1);
        assertThat(two.get()).isZero();
    }

    @Test
    void invokeCallback_withoutARecordedSubmitter_callsNobody() throws IOException {
        // No recorded submitter means no routing target: the update is dropped (with a warning)
        // rather than broadcast — no provider gets to see a registration that is not its own.
        var server = startReceiver();
        var one = countingEndpoint(server, "/one");
        var two = countingEndpoint(server, "/two");
        service.setCallbackAddress("client-1", callback(url(server, "/one")));
        service.setCallbackAddress("client-2", callback(url(server, "/two")));

        service.invokeCallback(OnboardingProcess.submitted("proc-1", "ext-1", null, null));

        assertThat(one.get()).isZero();
        assertThat(two.get()).isZero();
    }

    @Test
    void invokeCallback_forASubmitterWithoutARegistration_callsNobody() throws IOException {
        // The submitter never registered a callback: the update is dropped — it must NOT leak to
        // other providers' callbacks.
        var server = startReceiver();
        var one = countingEndpoint(server, "/one");
        service.setCallbackAddress("client-1", callback(url(server, "/one")));

        service.invokeCallback(OnboardingProcess.submitted("proc-1", "ext-1", null, null, "client-2"));

        assertThat(one.get()).isZero();
    }

    @Test
    void invokeCallback_withRegisteredCredentials_carriesAClientCredentialsToken() throws IOException {
        // A registration with an authUrl opts into authentication: the service fetches a token
        // via client_credentials from exactly that endpoint and sends it as the bearer.
        var server = startReceiver();
        var tokenRequests = new AtomicInteger();
        server.createContext("/token", exchange -> {
            tokenRequests.incrementAndGet();
            var body = "{\"access_token\":\"the-token\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var bearer = new AtomicReference<String>();
        server.createContext("/status", exchange -> {
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        service.setCallbackAddress("client-1",
                new CallbackRequestData(url(server, "/status"), url(server, "/token"), "cb-client", "cb-secret"));

        service.invokeCallback(OnboardingProcess.submitted("proc-1", "ext-1", null, null, "client-1"));

        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(bearer.get()).isEqualTo("Bearer the-token");
    }

    @Test
    void invokeCallback_whenTheTokenFetchFails_dropsTheUpdateInsteadOfCallingPlain() throws IOException {
        // The provider asked for authentication; an anonymous POST must never arrive at its
        // callback — a broken token endpoint drops the update (fire-and-forget, logged).
        var server = startReceiver();
        var status = countingEndpoint(server, "/status");
        server.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        service.setCallbackAddress("client-1",
                new CallbackRequestData(url(server, "/status"), url(server, "/token"), "cb-client", "cb-secret"));

        service.invokeCallback(OnboardingProcess.submitted("proc-1", "ext-1", null, null, "client-1"));

        assertThat(status.get()).isZero();
    }
}
