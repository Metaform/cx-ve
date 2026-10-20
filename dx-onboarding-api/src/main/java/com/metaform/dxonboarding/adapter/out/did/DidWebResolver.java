package com.metaform.dxonboarding.adapter.out.did;

import com.metaform.dxonboarding.domain.port.DidResolver;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves a {@code did:web} the way the platform's runtimes do (as the verification UI's resolver
 * does): the colon-separated identifier after {@code did:web:} becomes an authority plus a path,
 * the authority is percent-decoded (a non-default port arrives as {@code %3A8080}), and the
 * document is fetched from {@code /did.json} under that path — or {@code /.well-known/did.json}
 * when the identifier is a bare authority. The DID resolves when that document is JSON and
 * declares the DID as its {@code id} (compared without case, as the TSP keeps DIDs lower-cased).
 *
 * <p>The scheme is configuration rather than the method's mandated {@code https}: the platform
 * pins {@code edc.iam.did.web.use.https=false} across its runtimes, and the IssuerService that
 * later offers the participant its credentials resolves the DID the same way.
 */
public class DidWebResolver implements DidResolver {

    private static final String DID_WEB_PREFIX = "did:web:";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private static final Logger log = LoggerFactory.getLogger(DidWebResolver.class);

    private final RestClient restClient;
    private final String scheme;

    /** @param restClient a client without a base URL: every call is absolute, against whatever host the DID names */
    public DidWebResolver(RestClient restClient, String scheme) {
        this.restClient = restClient;
        this.scheme = scheme;
    }

    @Override
    public Optional<String> problem(String did) {
        String url;
        try {
            url = documentUrl(did);
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
        Map<String, Object> document;
        try {
            document = restClient.get().uri(URI.create(url)).retrieve().body(JSON_OBJECT);
        } catch (RestClientException e) {
            log.info("DID {} does not resolve at {}: {}", did, url, e.getMessage());
            return Optional.of("no DID document could be fetched from %s".formatted(url));
        }
        var id = document == null ? null : document.get("id");
        if (!(id instanceof String declared) || !declared.equalsIgnoreCase(did)) {
            return Optional.of("the DID document at %s declares the id '%s', not '%s'".formatted(url, id, did));
        }
        return Optional.empty();
    }

    /** The URL the document is served from, by the did:web method's own rules. */
    String documentUrl(String did) {
        if (did == null || !did.startsWith(DID_WEB_PREFIX)) {
            throw new IllegalArgumentException("'%s' is not a did:web — only did:web DIDs can be resolved here"
                    .formatted(did));
        }
        var segments = did.substring(DID_WEB_PREFIX.length()).split(":");
        if (segments.length == 0 || segments[0].isBlank()) {
            throw new IllegalArgumentException("'%s' names no host".formatted(did));
        }
        var authority = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
        if (segments.length == 1) {
            return "%s://%s/.well-known/did.json".formatted(scheme, authority);
        }
        var path = String.join("/", Arrays.copyOfRange(segments, 1, segments.length));
        return "%s://%s/%s/did.json".formatted(scheme, authority, path);
    }
}
