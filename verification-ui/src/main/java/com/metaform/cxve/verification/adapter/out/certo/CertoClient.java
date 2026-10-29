package com.metaform.cxve.verification.adapter.out.certo;

import com.metaform.cxve.verification.adapter.out.auth.TokenProvider;
import com.metaform.cxve.verification.adapter.out.http.HttpResult;
import com.metaform.cxve.verification.adapter.out.http.RestCalls;
import com.metaform.cxve.verification.application.Poller;
import com.metaform.cxve.verification.application.VerificationException;
import com.metaform.cxve.verification.config.VerificationProperties;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Client for Certo's management API (DSAF SCE), ported from the e2e suite's {@code CertoApi} — the
 * retry semantics are preserved exactly: a 5xx is transient (typically certo's siglet flow-token
 * resolution right after provisioning) and retried within the poll budget, a 4xx is a real request
 * error and aborts immediately. {@code flowId} parameters name the LIVE data flow (an EDC transfer
 * process of the CCM transfer type, in STARTED) whose Siglet cache entry supplies the token +
 * endpoint for the outbound SCE call — the caller side must be the DSP consumer of that flow.
 * Counterparties are addressed by their DID, and every outbound call names the counterparty's wire
 * protocol. Certo validates the jwtlet bearer itself (no clearglass on this route).
 */
@Component
public class CertoClient {

    private static final Logger log = LoggerFactory.getLogger(CertoClient.class);

    private final RestClient certoRestClient;
    private final TokenProvider tokenProvider;
    private final VerificationProperties properties;
    private final ObjectMapper mapper;

    public CertoClient(@Qualifier("certoRestClient") RestClient certoRestClient,
                       TokenProvider tokenProvider,
                       VerificationProperties properties,
                       ObjectMapper mapper) {
        this.certoRestClient = certoRestClient;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * Waits for the participant's Certo tenant to exist — the certo CFM agent creates it
     * asynchronously during provisioning, usually well before the onboarding completes, but
     * nothing guarantees the ordering.
     */
    public void awaitParticipantContext(String pcid) {
        log.info("waiting for certo participant context {}", pcid);
        Poller.poll("certo participant context " + pcid, properties.timeouts().certo(), properties.pollInterval(), () -> {
            var response = getPolled("/participant-contexts/" + pcid, "certo participant context");
            if (response.status() != 200) {
                throw new Poller.RetryException("certo participant context %s not there yet (HTTP %d)"
                        .formatted(pcid, response.status()));
            }
            return pcid;
        });
        log.info("certo participant context {} exists", pcid);
    }

    /** Uploads a certificate document into the provider tenant; returns the opaque documentId. */
    public String addDocument(String pcid, String mediaType, byte[] content) {
        var body = """
                {"mediaType": "%s", "contentBase64": "%s"}"""
                .formatted(mediaType, Base64.getEncoder().encodeToString(content));
        var response = post("/participant-contexts/%s/documents".formatted(pcid), body);
        expect2xx(response, "document upload");
        var documentId = json(response.body()).path("documentId").asText();
        log.info("document uploaded: {} ({}, {} bytes)", documentId, mediaType, content.length);
        return documentId;
    }

    /**
     * Issues a sample certificate of {@code certificateType} in the provider tenant referencing the
     * uploaded document (state change only, notifies no one), certified for the holder's legal
     * entity — the member id the run's dataspace assigned it. Returns the certificateId.
     */
    public String addCertificate(String pcid, String certificateType, String holderMemberId, String documentId,
                                 String registrationNumber, VerificationProperties.SampleCertificate sample) {
        var body = """
                {
                  "certificateType": "%s",
                  "certificateTypeVersion": "2015",
                  "registrationNumber": "%s",
                  "validFrom": "2026-01-01",
                  "validUntil": "2030-01-01",
                  "certifiedLocations": [{
                    "legalEntityId": "%s",
                    "locationRole": "MAIN_LOCATION"
                  }],
                  "issuer": {"issuerName": "%s", "issuerId": "%s"},
                  "documentIds": ["%s"]
                }""".formatted(certificateType, registrationNumber, holderMemberId, sample.issuerName(),
                sample.issuerId(), documentId);
        var response = post("/participant-contexts/%s/certificates".formatted(pcid), body);
        expect2xx(response, "certificate issuance");
        var certificateId = json(response.body()).path("certificateId").asText();
        log.info("certificate issued: {} ({}, holder {})", certificateId, certificateType, holderMemberId);
        return certificateId;
    }

    /**
     * Pushes the certificate's current lifecycle state (AVAILABLE) to the one named consumer over
     * {@code flowId}. Purely informational — it opens no exchange. Retried on 5xx and on
     * consumerNotified=false; {@code messageId} becomes the pushed event's id, so a repeat is one
     * the consumer de-duplicates.
     */
    public void notify(String pcid, String certificateId, String messageId, String protocol, String consumerDid,
                       String flowId) {
        var body = """
                {"messageId": "%s", "protocol": "%s", "consumerId": "%s", "flowId": "%s"}"""
                .formatted(messageId, protocol, consumerDid, flowId);
        var notification = Poller.poll("notification about certificate " + certificateId,
                properties.timeouts().certo(), properties.pollInterval(), () -> {
                    var response = postPolled(
                            "/participant-contexts/%s/certificates/%s/notify".formatted(pcid, certificateId),
                            body, "notify");
                    expectSuccessOrRetry(response, "notification about certificate " + certificateId);
                    var result = json(response.body());
                    if (!result.path("consumerNotified").asBoolean()) {
                        throw new Poller.RetryException("notify accepted but the consumer was NOT notified — "
                                + "the lifecycle CloudEvent to the consumer failed (flow token / siglet cache): " + result);
                    }
                    return result;
                });
        log.info("consumer {} notified: certificate {} r{} {}", consumerDid, certificateId,
                notification.path("revision").asInt(), notification.path("lifecycleStatus").asText());
    }

    /**
     * Consumer-initiated request on the consumer tenant: asks the provider over {@code flowId} for a
     * certificate of {@code certificateType} certified for {@code legalEntityId}. The provider answers
     * FULFILLED at once when it holds one, RECEIVED when its backend still has to issue it, or
     * DECLINED. Retried on 5xx; exchange creation is idempotent on (counterparty, {@code messageId}),
     * so a repeat with the SAME messageId returns the already-opened exchange.
     */
    public JsonNode requestCertificate(String pcid, String messageId, String protocol, String providerDid,
                                       String certificateType, String legalEntityId, String flowId) {
        var body = """
                {"messageId": "%s", "protocol": "%s", "providerId": "%s", "certificateType": "%s", \
                "certifiedLocations": [{"legalEntityId": "%s"}], "flowId": "%s"}"""
                .formatted(messageId, protocol, providerDid, certificateType, legalEntityId, flowId);
        var request = Poller.poll("certificate request to " + providerDid,
                properties.timeouts().certo(), properties.pollInterval(), () -> {
                    var response = postPolled("/participant-contexts/%s/consumer/certificate-requests".formatted(pcid),
                            body, "certificate request");
                    expectSuccessOrRetry(response, "certificate request to " + providerDid);
                    return json(response.body());
                });
        log.info("certificate requested from {}: exchange {} {}", providerDid,
                request.path("exchangeId").asText(), request.path("fulfillmentStatus").asText());
        return request;
    }

    /**
     * Polls the provider over {@code flowId} for the fulfillment status of a request this tenant
     * placed, and returns the consumer's refreshed view. Same 4xx-abort/5xx-retry contract, single-shot.
     */
    public JsonNode pollRequest(String pcid, String exchangeId, String flowId) {
        var response = postEmptyPolled("/participant-contexts/%s/consumer/certificate-requests/%s/poll?flowId=%s"
                .formatted(pcid, exchangeId, flowId), "fulfillment poll");
        expectSuccessOrRetry(response, "fulfillment poll of exchange " + exchangeId);
        return json(response.body());
    }

    /**
     * Client-driven retrieve on the consumer tenant: pulls the certificate metadata + document
     * binaries from the provider over {@code flowId}. Retried on 5xx; the pull is idempotent.
     */
    public JsonNode retrieve(String pcid, String exchangeId, String flowId) {
        var retrieved = Poller.poll("retrieve of exchange " + exchangeId,
                properties.timeouts().certo(), properties.pollInterval(), () -> {
                    var response = postEmptyPolled(
                            "/participant-contexts/%s/consumer/exchanges/%s/retrieve?flowId=%s"
                                    .formatted(pcid, exchangeId, flowId), "retrieve");
                    expectSuccessOrRetry(response, "retrieve of exchange " + exchangeId);
                    return json(response.body());
                });
        log.info("certificate retrieved: {} ({} document(s))",
                retrieved.path("certificate").path("certificateId").asText(), retrieved.path("documents").size());
        return retrieved;
    }

    /**
     * Client-driven terminal verdict on the consumer tenant, reported back over {@code flowId}.
     * Single-shot: 4xx aborts, 5xx raises a {@link Poller.RetryException} — the caller's own
     * poll loop re-drives it against the provider's recorded view (a repeat with the SAME
     * verdict skips the state transition and only re-reports, certo's documented recovery for a
     * lost best-effort acceptance report).
     */
    public void accept(String pcid, String exchangeId, String status, String flowId) {
        var body = """
                {"status": "%s", "flowId": "%s"}""".formatted(status, flowId);
        var response = postPolled(
                "/participant-contexts/%s/consumer/exchanges/%s/accept".formatted(pcid, exchangeId),
                body, "acceptance verdict");
        expectSuccessOrRetry(response, "acceptance verdict for exchange " + exchangeId);
        log.info("verdict {} recorded and reported for exchange {}", status, exchangeId);
    }

    /**
     * The consumer's reconciliation query: with {@code awaitingAcceptanceOnly} the exchanges still
     * needing this client's action (fulfilled and undecided, or decided but whose report never
     * landed), otherwise every exchange of the tenant. When the provider is a third-party system,
     * this is the only record of the verdict this environment can read.
     */
    public JsonNode consumerExchanges(String pcid, boolean awaitingAcceptanceOnly) {
        var body = """
                {"awaitingAcceptanceOnly": %b}""".formatted(awaitingAcceptanceOnly);
        var response = postPolled("/participant-contexts/%s/consumer/exchanges/query".formatted(pcid),
                body, "consumer exchange query");
        expectSuccessOrRetry(response, "consumer exchange query in " + pcid);
        return json(response.body());
    }

    /** The provider's recorded view of both exchange phases; same 4xx-abort/5xx-retry contract. */
    public JsonNode getExchange(String pcid, String exchangeId) {
        var response = getPolled("/participant-contexts/%s/certificate-exchanges/%s".formatted(pcid, exchangeId),
                "provider exchange view");
        expectSuccessOrRetry(response, "provider exchange view of " + exchangeId);
        return json(response.body());
    }

    private HttpResult post(String path, String body) {
        return RestCalls.post(certoRestClient, path, token(), body);
    }

    private HttpResult postPolled(String path, String body, String what) {
        try {
            return post(path, body);
        } catch (ResourceAccessException e) {
            throw new Poller.RetryException(what + ": certo unreachable: " + e.getMessage(), e);
        }
    }

    private HttpResult postEmptyPolled(String path, String what) {
        try {
            return RestCalls.post(certoRestClient, path, token());
        } catch (ResourceAccessException e) {
            throw new Poller.RetryException(what + ": certo unreachable: " + e.getMessage(), e);
        }
    }

    private HttpResult getPolled(String path, String what) {
        try {
            return RestCalls.get(certoRestClient, path, token());
        } catch (ResourceAccessException e) {
            throw new Poller.RetryException(what + ": certo unreachable: " + e.getMessage(), e);
        }
    }

    private String token() {
        return tokenProvider.getToken(properties.certoAuth().tokenResource(), properties.certoAuth().tokenScope());
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            throw new VerificationException("unparseable certo response: " + body, e);
        }
    }

    private static void expect2xx(HttpResult response, String what) {
        if (!response.is2xx()) {
            throw new VerificationException("%s failed with HTTP %d: %s"
                    .formatted(what, response.status(), response.body()));
        }
    }

    /**
     * 5xx = transient (typically certo's siglet flow-token resolution) and retryable; 4xx = a
     * real request error and aborts the poll immediately.
     */
    private static void expectSuccessOrRetry(HttpResult response, String what) {
        if (response.is4xx()) {
            throw new VerificationException("%s failed with HTTP %d: %s"
                    .formatted(what, response.status(), response.body()));
        }
        if (!response.is2xx()) {
            throw new Poller.RetryException("%s failed with HTTP %d (transient?): %s"
                    .formatted(what, response.status(), response.body()));
        }
    }
}
