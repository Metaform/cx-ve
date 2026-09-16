package com.metaform.cxve.verification.adapter.out.did;

import com.metaform.cxve.verification.application.VerificationException;
import com.metaform.cxve.verification.domain.model.DidDocument;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves a {@code did:web} the way the platform's runtimes do, so what this app sees is what
 * they will see when they talk to the same participant. Per the did:web method: the
 * colon-separated identifier after {@code did:web:} becomes an authority plus a path, the
 * authority is percent-decoded (a non-default port arrives as {@code %3A8080}), and the document
 * is fetched from {@code /did.json} under that path — or {@code /.well-known/did.json} when the
 * identifier is a bare authority.
 *
 * <p>The scheme is configuration rather than the method's mandated {@code https}: the platform
 * pins {@code edc.iam.did.web.use.https=false} across its runtimes, and a resolver that
 * disagreed with them would either accept a participant they cannot reach or reject one they
 * can. It follows that setting; a deployment that moves the platform to TLS moves this with it.
 */
@Component
public class DidWebResolver {

    private static final String DID_WEB_PREFIX = "did:web:";

    private static final Logger log = LoggerFactory.getLogger(DidWebResolver.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String scheme;

    public DidWebResolver(ObjectMapper mapper,
                          @Value("${verification.external.did-web-scheme:http}") String scheme) {
        // No base URL: every call is absolute, against whatever host the DID names.
        this.restClient = RestClient.builder().build();
        this.mapper = mapper;
        this.scheme = scheme;
    }

    /**
     * Fetches and reads the document. Anything that stops a participant from being addressable —
     * an identifier that is not a {@code did:web}, an unreachable or non-JSON document, a missing
     * service entry — fails the resolution with a message naming what is wrong, because that is
     * the finding, not an incident.
     */
    public DidDocument resolve(String did) {
        var url = documentUrl(did);
        log.info("resolving {} at {}", did, url);
        String body;
        try {
            var response = restClient.get().uri(url).retrieve().toEntity(String.class);
            body = response.getBody();
        } catch (ResourceAccessException e) {
            throw new VerificationException("DID document of %s is unreachable at %s: %s"
                    .formatted(did, url, e.getMessage()), e);
        } catch (RuntimeException e) {
            throw new VerificationException("DID document of %s could not be fetched from %s: %s"
                    .formatted(did, url, e.getMessage()), e);
        }
        JsonNode document;
        try {
            document = mapper.readTree(body);
        } catch (JacksonException e) {
            throw new VerificationException("DID document of %s at %s is not valid JSON".formatted(did, url), e);
        }
        var documentId = document.path("id").asText();
        if (!did.equals(documentId)) {
            throw new VerificationException("DID document at %s declares id '%s', not the resolved '%s'"
                    .formatted(url, documentId, did));
        }
        var resolved = new DidDocument(did,
                serviceEndpoint(document, "ProtocolEndpoint", did),
                serviceEndpoint(document, "CredentialService", did));
        log.info("resolved {}: dsp={}, credential service={}",
                did, resolved.protocolEndpoint(), resolved.credentialServiceEndpoint());
        return resolved;
    }

    /** The URL the document is served from, by the did:web method's own rules. */
    // Package-private: pinned by tests, since a wrong URL would misreport a healthy participant.
    String documentUrl(String did) {
        if (did == null || !did.startsWith(DID_WEB_PREFIX)) {
            throw new VerificationException(
                    "'%s' is not a did:web — this environment can only resolve did:web participants".formatted(did));
        }
        var segments = did.substring(DID_WEB_PREFIX.length()).split(":");
        if (segments.length == 0 || segments[0].isBlank()) {
            throw new VerificationException("'%s' carries no authority".formatted(did));
        }
        var authority = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
        if (segments.length == 1) {
            return "%s://%s/.well-known/did.json".formatted(scheme, authority);
        }
        var path = String.join("/", java.util.Arrays.copyOfRange(segments, 1, segments.length));
        return "%s://%s/%s/did.json".formatted(scheme, authority, path);
    }

    private static String serviceEndpoint(JsonNode document, String type, String did) {
        for (var service : document.path("service")) {
            if (type.equals(service.path("type").asText())) {
                var endpoint = service.path("serviceEndpoint").asText();
                if (endpoint.isBlank()) {
                    throw new VerificationException("DID document of %s advertises a %s without a serviceEndpoint"
                            .formatted(did, type));
                }
                return endpoint;
            }
        }
        throw new VerificationException(("DID document of %s advertises no %s service — this environment "
                + "cannot reach the participant without it").formatted(did, type));
    }
}
