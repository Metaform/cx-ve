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
 * Client for Certo's management API (CX-0135 CCM), ported from the e2e suite's {@code CertoApi}
 * — the retry semantics are preserved exactly: a 5xx is transient (typically certo's siglet
 * flow-token resolution right after provisioning) and retried within the poll budget, a 4xx is a
 * real request error and aborts immediately. {@code flowId} parameters name the LIVE data flow
 * (an EDC transfer process of the CCM transfer type, in STARTED) whose Siglet cache entry
 * supplies the token + endpoint for the outbound CCM call — the caller side must be the DSP
 * consumer of that flow. Certo validates the jwtlet bearer itself (no clearglass on this route).
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
     * Issues a sample ISO9001 certificate in the provider tenant referencing the uploaded
     * document (state change only, notifies no one). Returns the certificateId.
     */
    public String addCertificate(String pcid, String holderBpn, String documentId, String registrationNumber) {
        var body = """
                {
                  "certificateType": "ISO9001",
                  "certificateTypeVersion": "2015",
                  "registrationNumber": "%s",
                  "validFrom": "2026-01-01",
                  "validUntil": "2030-01-01",
                  "trustLevel": "high",
                  "certifiedLocations": [{
                    "bpnl": "%s",
                    "bpna": "BPNA00000000MAIN0",
                    "locationRole": "MAIN_LOCATION"
                  }],
                  "issuer": {"issuerName": "CXVE verification CA", "issuerBpn": "BPNL00000000ISSUER"},
                  "documentIds": ["%s"]
                }""".formatted(registrationNumber, holderBpn, documentId);
        var response = post("/participant-contexts/%s/certificates".formatted(pcid), body);
        expect2xx(response, "certificate issuance");
        var certificateId = json(response.body()).path("certificateId").asText();
        log.info("certificate issued: {} (ISO9001, holder {})", certificateId, holderBpn);
        return certificateId;
    }

    /**
     * Flow B publish (v3.0.0, not embedded): opens a FULFILLED exchange and pushes the lifecycle
     * CREATED event to the named consumer over {@code flowId}. Returns the exchangeId once the
     * consumer was actually notified; retried on 5xx and on consumerNotified=false — the stable
     * idempotencyKey makes a repeat reuse the SAME exchange and just re-notify.
     */
    public String publish(String pcid, String certificateId, String consumerBpn, String consumerDid, String flowId) {
        var body = """
                {"consumerBpn": "%s", "consumerDid": "%s", "flowId": "%s", "idempotencyKey": "vui-%s", \
                "protocolVersion": "3.0.0", "embedded": false}"""
                .formatted(consumerBpn, consumerDid, flowId, certificateId);
        var publication = Poller.poll("publish of certificate " + certificateId,
                properties.timeouts().certo(), properties.pollInterval(), () -> {
                    var response = postPolled(
                            "/participant-contexts/%s/certificates/%s/publish".formatted(pcid, certificateId),
                            body, "publish");
                    expectSuccessOrRetry(response, "publish of certificate " + certificateId);
                    var result = json(response.body());
                    if (!result.path("consumerNotified").asBoolean()) {
                        throw new Poller.RetryException("publish accepted but the consumer was NOT notified — "
                                + "the push CloudEvent to the consumer failed (flow token / siglet cache): " + result);
                    }
                    return result;
                });
        var exchangeId = publication.path("exchangeId").asText();
        log.info("certificate published, consumer notified (exchange {})", exchangeId);
        return exchangeId;
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
