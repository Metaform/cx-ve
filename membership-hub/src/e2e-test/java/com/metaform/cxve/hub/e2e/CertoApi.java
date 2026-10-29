package com.metaform.cxve.hub.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.metaform.cxve.hub.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Minimal client for Certo's management API (DSAF SCE, wire protocol {@value #PROTOCOL}) through the VE gateway
 * ({@code http://<host>/api/certo/management/v1}). There is no clearglass on this route — Certo
 * validates the jwtlet bearer token itself (scope {@code certo-mgmt-api:write} covers all
 * management calls). {@code flowId} parameters name the LIVE data flow (an EDC transfer process
 * of the CCM transfer type, in STARTED) whose Siglet cache entry supplies the token + endpoint
 * for the outbound SCE call the management action triggers — the caller side must be the DSP
 * consumer of that flow, since only the DSP consumer's cache is populated.
 */
public class CertoApi {

    /** The counterparty's wire-protocol id, stated on every management call that goes out. */
    static final String PROTOCOL = "sce-1.0";

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
     * Issues an {@code iso9001} certificate in the provider tenant, its main location the holder's
     * legal entity, referencing the uploaded document (state change only, notifies no one).
     * Returns the certificateId.
     */
    public String addCertificate(String pcid, String holderLegalEntityId, String documentId) {
        var body = """
                {
                  "certificateType": "iso9001",
                  "certificateTypeVersion": "2015",
                  "registrationNumber": "CXVE-E2E-9001",
                  "validFrom": "2026-01-01",
                  "validUntil": "2030-01-01",
                  "certifiedLocations": [{
                    "legalEntityId": "%s",
                    "locationRole": "MAIN_LOCATION"
                  }],
                  "issuer": {"issuerName": "CXVE e2e CA", "issuerId": "DX-ISSUER"},
                  "documentIds": ["%s"]
                }""".formatted(holderLegalEntityId, documentId);
        var response = post("/participant-contexts/%s/certificates".formatted(pcid), body);
        expect2xx(response, "certificate");
        var created = json(response);
        var certificateId = created.path("certificateId").asText();
        log("   Certo API:       certificate issued: %s (iso9001 r%d, holder %s)",
                certificateId, created.path("revision").asInt(), holderLegalEntityId);
        return certificateId;
    }

    /**
     * Single-target lifecycle push: notifies the named consumer of the certificate's current
     * lifecycle state ({@code AVAILABLE} for a fresh certificate) over {@code flowId}. Lifecycle
     * only — it opens no exchange. Asserts the consumer was actually notified (the push CloudEvent
     * was delivered 2xx). Retried on 5xx and on consumerNotified=false: certo resolves the flow
     * token from siglet on every attempt, and that resolution can fail transiently right after
     * provisioning (siglet authenticates to Vault per participant context via a jwtlet mapping the
     * siglet agent has only just created). Every attempt carries the SAME messageId, so the
     * consumer dedups a re-send as the same event.
     */
    public void notify(String pcid, String certificateId, String consumerDid, String flowId) {
        var body = """
                {"messageId": "%s", "protocol": "%s", "consumerId": "%s", "flowId": "%s"}"""
                .formatted(UUID.randomUUID(), PROTOCOL, consumerDid, flowId);
        var result = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(2)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = post("/participant-contexts/%s/certificates/%s/notify".formatted(pcid, certificateId), body);
            expectSuccessOrRetry(response, "notify of certificate " + certificateId);
            var notification = json(response);
            assertThat(notification.path("consumerNotified").asBoolean())
                    .withFailMessage("notify accepted but the consumer was NOT notified — the push "
                                     + "CloudEvent to the consumer API failed "
                                     + "(check the flow token / siglet cache / certo logs): %s", notification)
                    .isTrue();
            result.set(notification);
        });
        log("   Certo API:       consumer notified: certificate %s %s r%d", certificateId,
                result.get().path("lifecycleStatus").asText(), result.get().path("revision").asInt());
    }

    /**
     * Client-driven retrieve on the consumer tenant: pulls the certificate metadata + document
     * binaries from the provider over {@code flowId} for inspection before the verdict.
     * Retried on 5xx (see {@link #notify}); the pull is idempotent.
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
     * Opens a certificate request on the consumer tenant: asks the provider (by DID) for a
     * certificate of {@code certificateType} whose locations cover {@code legalEntityId}. A
     * certificate the provider already holds fulfills it immediately ({@code FULFILLED}, the
     * certificate identity disclosed); otherwise the exchange waits in {@code RECEIVED} until the
     * provider fulfills it.
     *
     * <p>Retried on 5xx for the same reason as {@link #notify}: the outbound request resolves a
     * flow token from siglet, which can fail transiently just after provisioning. Every attempt
     * carries the SAME messageId, on which the provider's exchange creation is idempotent, so a
     * retry gets the already-created exchange back rather than opening a duplicate.
     *
     * @return the consumer's request view ({@code exchangeId}, {@code fulfillmentStatus}, ...)
     */
    public JsonNode requestCertificate(String pcid, String providerDid, String certificateType,
                                       String legalEntityId, String flowId) {
        var body = """
                {"messageId": "%s", "protocol": "%s", "providerId": "%s", "certificateType": "%s", \
                "certifiedLocations": [{"legalEntityId": "%s"}], "flowId": "%s"}"""
                .formatted(UUID.randomUUID(), PROTOCOL, providerDid, certificateType, legalEntityId, flowId);
        var result = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(2)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = post("/participant-contexts/%s/consumer/certificate-requests".formatted(pcid), body);
            expectSuccessOrRetry(response, "certificate request to " + providerDid);
            result.set(json(response));
        });
        var request = result.get();
        log("   Certo API:       certificate request opened (exchange %s, status %s)",
                request.path("exchangeId").asText(), request.path("fulfillmentStatus").asText());
        return request;
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
