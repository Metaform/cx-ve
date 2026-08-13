package com.metaform.cxve.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.metaform.cxve.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Minimal client for Certo's management API (CX-0135 CCM) through the VE gateway
 * ({@code http://<host>/api/certo/management/v1}). There is no clearglass on this route — Certo
 * validates the jwtlet bearer token itself (scope {@code certo-mgmt-api:write} covers all
 * management calls). {@code flowId} parameters name the LIVE data flow (an EDC transfer process
 * of the CCM transfer type, in STARTED) whose Siglet cache entry supplies the token + endpoint
 * for the outbound CCM call the management action triggers — the caller side must be the DSP
 * consumer of that flow, since only the DSP consumer's cache is populated.
 */
public class CertoApi {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();

    public CertoApi(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /**
     * Waits for the participant's Certo tenant to exist. The certo CFM agent creates it
     * asynchronously (NATS activity stream) during provisioning, usually well before the
     * onboarding completes — but nothing guarantees the ordering, so poll.
     */
    public void awaitParticipantContext(String pcid, Duration timeout) {
        log("   Certo API:       waiting for participant context %s...", pcid);
        await().atMost(timeout).pollInterval(POLL_INTERVAL).untilAsserted(() ->
                assertThat(get("/participant-contexts/%s".formatted(pcid)).statusCode()).isEqualTo(200));
        log("   Certo API:       participant context %s exists", pcid);
    }

    /**
     * Uploads a certificate document (opaque binary to Certo) into the provider tenant;
     * returns the opaque documentId the certificate references it by.
     */
    public String addDocument(String pcid, String mediaType, byte[] content) {
        var body = """
                {"mediaType": "%s", "contentBase64": "%s"}"""
                .formatted(mediaType, Base64.getEncoder().encodeToString(content));
        var response = post("/participant-contexts/%s/documents".formatted(pcid), body);
        expect2xx(response, "document");
        var documentId = json(response).path("documentId").asText();
        log("   Certo API:       document uploaded: %s (%s, %d bytes)", documentId, mediaType, content.length);
        return documentId;
    }

    /**
     * Issues a certificate in the provider tenant referencing the uploaded document (state
     * change only, notifies no one). Returns the certificateId.
     */
    public String addCertificate(String pcid, String holderBpn, String documentId) {
        var body = """
                {
                  "certificateType": "ISO9001",
                  "certificateTypeVersion": "2015",
                  "registrationNumber": "CXVE-E2E-9001",
                  "validFrom": "2026-01-01",
                  "validUntil": "2030-01-01",
                  "trustLevel": "high",
                  "certifiedLocations": [{
                    "bpnl": "%s",
                    "bpna": "BPNA00000000MAIN0",
                    "locationRole": "MAIN_LOCATION"
                  }],
                  "issuer": {"issuerName": "CXVE e2e CA", "issuerBpn": "BPNL00000000ISSUER"},
                  "documentIds": ["%s"]
                }""".formatted(holderBpn, documentId);
        var response = post("/participant-contexts/%s/certificates".formatted(pcid), body);
        expect2xx(response, "certificate");
        var certificateId = json(response).path("certificateId").asText();
        log("   Certo API:       certificate issued: %s (ISO9001, holder %s)", certificateId, holderBpn);
        return certificateId;
    }

    /**
     * Flow B publish: opens a FULFILLED exchange and pushes a lifecycle CREATED event to the
     * named consumer over {@code flowId}. Returns the exchangeId; asserts the consumer was
     * actually notified (the push CloudEvent was delivered 2xx). Retried on 5xx and on
     * consumerNotified=false: certo resolves the flow token from siglet on every attempt, and
     * that resolution can fail transiently right after provisioning (siglet authenticates to
     * Vault per participant context via a jwtlet mapping the siglet agent has only just
     * created). The stable idempotencyKey makes the repeat reuse the SAME exchange and just
     * re-notify instead of opening a duplicate.
     */
    public String publish(String pcid, String certificateId, String consumerBpn, String consumerDid, String flowId) {
        var body = """
                {"consumerBpn": "%s", "consumerDid": "%s", "flowId": "%s", "idempotencyKey": "e2e-%s"}"""
                .formatted(consumerBpn, consumerDid, flowId, certificateId);
        var result = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(2)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = post("/participant-contexts/%s/certificates/%s/publish".formatted(pcid, certificateId), body);
            expectSuccessOrRetry(response, "publish of certificate " + certificateId);
            var publication = json(response);
            assertThat(publication.path("consumerNotified").asBoolean())
                    .withFailMessage("publish accepted but the consumer was NOT notified — the push "
                                     + "CloudEvent to the consumer's certificate-notifications endpoint failed "
                                     + "(check the flow token / siglet cache / certo logs): %s", publication)
                    .isTrue();
            result.set(publication);
        });
        var exchangeId = result.get().path("exchangeId").asText();
        log("   Certo API:       certificate published, consumer notified (exchange %s)", exchangeId);
        return exchangeId;
    }

    /**
     * Client-driven retrieve on the consumer tenant: pulls the certificate metadata + document
     * binaries from the provider over {@code flowId} for inspection before the verdict.
     * Retried on 5xx (see {@link #publish}); the pull is idempotent.
     */
    public JsonNode retrieve(String pcid, String exchangeId, String flowId) {
        var result = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(2)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = given()
                    .baseUri(baseUrl)
                    .header("Authorization", "Bearer " + token)
                    .queryParam("flowId", flowId)
                    .post("/participant-contexts/%s/consumer/exchanges/%s/retrieve".formatted(pcid, exchangeId));
            expectSuccessOrRetry(response, "retrieve of exchange " + exchangeId);
            result.set(json(response));
        });
        var retrieved = result.get();
        log("   Certo API:       certificate retrieved: %s (%d document(s))",
                retrieved.path("certificate").path("certificateId").asText(), retrieved.path("documents").size());
        return retrieved;
    }

    /**
     * Client-driven terminal verdict on the consumer tenant, reported back over {@code flowId}.
     * Safe to re-drive: a repeat with the SAME verdict skips the state transition and only
     * re-reports to the provider — certo's documented recovery for a lost (best-effort,
     * post-commit) acceptance report. Callers poll the provider's {@link #getExchange} view and
     * re-drive until the verdict shows up there.
     */
    public void accept(String pcid, String exchangeId, String status, String flowId) {
        var body = """
                {"status": "%s", "flowId": "%s"}""".formatted(status, flowId);
        var response = post("/participant-contexts/%s/consumer/exchanges/%s/accept".formatted(pcid, exchangeId), body);
        expectSuccessOrRetry(response, "acceptance verdict for exchange " + exchangeId);
        log("   Certo API:       verdict %s recorded and reported for exchange %s", status, exchangeId);
    }

    /**
     * Opens a consumer-INITIATED request on the consumer tenant: asks the provider for a certificate
     * of {@code certificateType} covering {@code locations}. Unlike {@link #publish}, which opens an
     * exchange already {@code FULFILLED}, this enters the CX-0135 §2.1.3 state machine at its start —
     * the provider has nothing to deliver yet.
     *
     * <p>Retried on 5xx for the same reason as {@link #publish}: the outbound request-open resolves a
     * flow token from siglet, which can fail transiently just after provisioning. certo resolves a
     * repeat as find-or-create over a still-live exchange, so the retry reuses it rather than opening
     * a duplicate.
     *
     * @return the opened exchange id
     */
    public String initiateRequest(String pcid, String providerBpn, String providerDid,
                                  String certificateType, List<String> locations, String flowId) {
        var quotedLocations = locations.stream().map("\"%s\""::formatted).collect(joining(", "));
        var body = """
                {"providerBpn": "%s", "providerDid": "%s", "certificateType": "%s", \
                "certifiedLocations": [%s], "flowId": "%s"}"""
                .formatted(providerBpn, providerDid, certificateType, quotedLocations, flowId);
        var result = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(2)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = post("/participant-contexts/%s/consumer/certificate-requests".formatted(pcid), body);
            expectSuccessOrRetry(response, "certificate request to " + providerBpn);
            result.set(json(response));
        });
        var request = result.get();
        var exchangeId = request.path("exchangeId").asText();
        log("   Certo API:       certificate request opened (exchange %s, status %s)",
                exchangeId, request.path("fulfillmentStatus").asText());
        return exchangeId;
    }

    /** The consumer's tracked view of a request it opened. */
    public JsonNode getRequest(String pcid, String exchangeId) {
        var response = get("/participant-contexts/%s/consumer/certificate-requests/%s".formatted(pcid, exchangeId));
        expectSuccessOrRetry(response, "consumer request view of " + exchangeId);
        return json(response);
    }

    /**
     * Polls the provider for the current fulfillment status and mirrors it onto the consumer's record.
     * Idempotent by design — certo only records a change when the status actually moved, so repeated
     * polling neither transitions anything nor re-emits events.
     */
    public JsonNode pollRequest(String pcid, String exchangeId, String flowId) {
        var result = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(2)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = given()
                    .baseUri(baseUrl)
                    .header("Authorization", "Bearer " + token)
                    .queryParam("flowId", flowId)
                    .post("/participant-contexts/%s/consumer/certificate-requests/%s/poll".formatted(pcid, exchangeId));
            expectSuccessOrRetry(response, "poll of request " + exchangeId);
            result.set(json(response));
        });
        return result.get();
    }

    /**
     * Fulfills a waiting request on the provider tenant with a certificate it now holds, pushing
     * {@code FULFILLED} to the consumer over its live {@code flowId}. This is the transition that
     * binds the certificate identity to the exchange — until now it had none.
     */
    public JsonNode fulfillRequest(String pcid, String exchangeId, String flowId) {
        var result = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(2)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = given()
                    .baseUri(baseUrl)
                    .header("Authorization", "Bearer " + token)
                    .queryParam("flowId", flowId)
                    .post("/participant-contexts/%s/certificate-requests/%s/fulfill".formatted(pcid, exchangeId));
            expectSuccessOrRetry(response, "fulfillment of request " + exchangeId);
            result.set(json(response));
        });
        log("   Certo API:       request %s fulfilled", exchangeId);
        return result.get();
    }

    /** The requests a provider-held certificate could satisfy — the provider's backlog view. */
    public JsonNode fulfillableRequests(String pcid, String certificateId) {
        var response = get("/participant-contexts/%s/certificates/%s/fulfillable-requests".formatted(pcid, certificateId));
        expectSuccessOrRetry(response, "fulfillable requests for certificate " + certificateId);
        return json(response);
    }

    /** The provider's recorded view of both exchange phases (management/inspection endpoint). */
    public JsonNode getExchange(String pcid, String exchangeId) {
        var response = get("/participant-contexts/%s/certificate-exchanges/%s".formatted(pcid, exchangeId));
        expectSuccessOrRetry(response, "provider exchange view of " + exchangeId);
        return json(response);
    }

    private Response post(String path, String body) {
        return given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body)
                .post(path);
    }

    private Response get(String path) {
        return given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer " + token)
                .get(path);
    }

    private JsonNode json(Response response) {
        try {
            return mapper.readTree(response.asString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void expect2xx(Response response, String what) {
        assertThat(response.statusCode())
                .withFailMessage("%s failed with HTTP %d: %s", what, response.statusCode(), response.asString())
                .isBetween(200, 299);
    }

    /**
     * 5xx = transient (typically certo's siglet flow-token resolution) and fails the current
     * awaitility attempt for a retry; 4xx = a real request error and aborts the poll
     * immediately (IllegalStateException is not an AssertionError, so awaitility rethrows).
     */
    private static void expectSuccessOrRetry(Response response, String what) {
        var status = response.statusCode();
        if (status >= 400 && status < 500) {
            throw new IllegalStateException("%s failed with HTTP %d: %s".formatted(what, status, response.asString()));
        }
        assertThat(status)
                .withFailMessage("%s failed with HTTP %d (transient?): %s", what, status, response.asString())
                .isBetween(200, 299);
    }
}
