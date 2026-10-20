package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.hub.adapter.out.onboarding.ClientCredentials;
import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * DECADE-X onboarding through the TSP's onboarding intake (the VE's dx-onboarding-api), in the
 * intake's three steps: the hub creates a draft from the member's {@code registration} object —
 * completed with the member's legal name, DID and the hub's external id as
 * {@code applicantReference} — adds placeholder documents to it (the GTC, one UCA per use case),
 * and submits it. Every change sends the draft's current version ({@code If-Match}); the create and
 * the submit carry an {@code Idempotency-Key} derived from the external id, so a step retried after
 * a lost response returns what the first attempt did. Each step is retried once when the TSP
 * cannot be reached or answers 502/503. A refusal is an RFC 9457 problem: the TSP's verdict on the
 * registration ({@code invalid-request}, {@code onboarding-request-submit-rejected}) becomes an
 * {@link InvalidRegistrationException} naming every field it refused, anything else an
 * {@link IllegalStateException}; a draft that could not be submitted is deleted again.
 *
 * <p>The TSP has no status callbacks: the hub {@linkplain #pollsStatus() polls} the request's
 * status instead. {@code APPROVED} maps to the hub's CONFIRMED — like Catena-X's confirmation, it
 * means the TSP registered the participant as credential holder and had the issuer offer it the
 * DecadeXMembershipCredential — and carries the participant's DECADE-X-ID; {@code REJECTED} and
 * {@code APPROVAL_FAILED} (the provisioning after an approval failed, which nothing retries) map to
 * DECLINED, and every other status — a draft, the steps of the review — to PENDING.
 *
 * <p>The TSP assigns every DECADE-X-ID on approval, and the confirmation hands it to the hub, so a
 * member declares none. A member hosted HERE needs one before that: its deployment reads it (certo
 * does). The hub generates one for it ({@link #memberIdForHostedMember()}) and sends it along as
 * {@code legalEntity.legalEntityId} — a VE extension of the TSP's request, which the VE's TSP
 * honors for the participants it hosts only — so its credential carries the id it was deployed with.
 *
 * <p>In the dataspace, a participant reaches the TSP as an asset of the federated connector: its
 * data endpoint is what {@code onboarding.url} names, and its data plane stamps the calling
 * connector's identity on every call. The hub calls the TSP directly and stands in for that data
 * plane: it sends the member's DID as the identity, in the header the TSP is ASSUMED to read it
 * from ({@value #CONNECTOR_ID_HEADER} — the TSP's specification does not say how the identity
 * reaches it). Requests are scoped to that identity, so the status is read under the same one.
 */
public class DecadeXOnboarding implements DataspaceOnboarding {

    public static final String DATASPACE = "decade-x";

    static final String CONNECTOR_ID_HEADER = "X-Connector-Id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String DECADE_X_ID = "DX-[0-9]{8}";
    /** The TSP's bound on {@code legalEntity.legalName}, which the member's name is sent as. */
    static final int MAX_NAME_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(DecadeXOnboarding.class);

    // A plain mapper, like the RegistrationValidator's: the registration's shape is independent of
    // the web layer's JSON configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RandomGenerator random = new SecureRandom();
    private final RestClient restClient;
    private final ClientCredentials credentials;
    private final RegistrationValidator validator;

    public DecadeXOnboarding(DataspaceProperties.Onboarding onboarding, RegistrationValidator validator) {
        this(RestClient.builder(), onboarding, new ClientCredentials(onboarding.auth()), validator);
    }

    /** @param onboarding its {@code url} is the TSP onboarding data endpoint: the onboarding requests themselves */
    DecadeXOnboarding(RestClient.Builder restClient, DataspaceProperties.Onboarding onboarding,
                      ClientCredentials credentials, RegistrationValidator validator) {
        this.restClient = restClient.baseUrl(onboarding.url()).build();
        this.credentials = credentials;
        this.validator = validator;
    }

    @Override
    public String dataspace() {
        return DATASPACE;
    }

    /**
     * Besides the registration object: no member declares a DECADE-X-ID (the TSP assigns it), and
     * the member's name fits the TSP's {@code legalName}.
     */
    @Override
    public void validate(MemberData data) {
        if (data.memberId() != null && !data.memberId().isBlank()) {
            throw new InvalidRegistrationException(
                    "memberId must be left out: a DECADE-X-ID is assigned by the TSP on approval");
        }
        if (data.name() != null && data.name().length() > MAX_NAME_LENGTH) {
            throw new InvalidRegistrationException(
                    "name must be at most %d characters (it is the TSP's legalEntity.legalName)".formatted(MAX_NAME_LENGTH));
        }
        registration(data);
    }

    /** A random DECADE-X-ID: the hub's stand-in for the one the TSP would assign on approval. */
    @Override
    public Optional<String> memberIdForHostedMember() {
        return Optional.of("DX-%08d".formatted(random.nextInt(100_000_000)));
    }

    /**
     * DECADE-X membership is the dataspace itself; the DECADE-X-ID is carried under {@code bpn}
     * too, because that is the key the certo provisioning activity reads a member's id from.
     */
    @Override
    public Map<String, Object> issuerProperties(String did, MemberData data) {
        return Map.of(
                "id", did,
                "memberOf", "DECADE-X",
                "decadeXId", data.memberId(),
                "bpn", data.memberId());
    }

    /** Nothing to register: the TSP has no status callbacks — its status is polled. */
    @Override
    public void registerCallback() {
    }

    @Override
    public String submitRegistration(String externalId, String did, MemberData data) {
        var registration = registration(data);
        Draft draft;
        try {
            draft = create(externalId, did, request(externalId, did, data));
        } catch (Refusal refusal) {
            throw refusal.translated();
        }
        if (!"DRAFT".equals(draft.status())) {
            // an earlier attempt under the same key got as far as the submit
            log.info("DECADE-X onboarding request '{}' ({}) of '{}' is already {}", draft.id(), draft.businessId(),
                    externalId, draft.status());
            return draft.id();
        }
        try {
            var version = addDocuments(draft.id(), did, placeholders(registration, data.name()));
            var submitted = submit(externalId, did, draft.id(), version);
            log.info("DECADE-X onboarding request '{}' ({}) submitted for '{}': {}", draft.id(), draft.businessId(),
                    externalId, submitted.status());
            return draft.id();
        } catch (RuntimeException e) {
            discard(draft.id(), did);
            throw e instanceof Refusal refusal ? refusal.translated() : e;
        }
    }

    /** Callbacks are not part of the TSP's API; the hub refuses them for a polled dataspace. */
    @Override
    public RegistrationOutcome readCallback(Map<String, Object> body) {
        throw new UnsupportedOperationException("The DECADE-X onboarding API reports no callbacks; its status is polled");
    }

    @Override
    public boolean pollsStatus() {
        return true;
    }

    @Override
    public RegistrationOutcome pollStatus(Membership membership) {
        var request = restClient.get()
                .uri("/{id}", membership.onboardingProcessId())
                .headers(headers -> authorize(headers, membership.did()))
                .retrieve()
                .body(RequestStatus.class);
        if (request == null || request.status() == null) {
            throw new IllegalStateException("The DECADE-X onboarding API returned no status for request "
                    + membership.onboardingProcessId());
        }
        log.debug("DECADE-X onboarding request '{}' of '{}': {}", membership.onboardingProcessId(),
                membership.externalId(), request.status());
        var reason = request.decision() == null ? null : request.decision().describe();
        return switch (request.status()) {
            case "APPROVED" -> new RegistrationOutcome(membership.externalId(), RegistrationOutcome.Status.CONFIRMED,
                    null, request.legalEntity() == null ? null : request.legalEntity().legalEntityId());
            case "REJECTED" -> new RegistrationOutcome(membership.externalId(), RegistrationOutcome.Status.DECLINED,
                    reason);
            case "APPROVAL_FAILED" -> new RegistrationOutcome(membership.externalId(),
                    RegistrationOutcome.Status.DECLINED, "Approved, but provisioning the membership failed"
                    + (reason == null ? "" : ": " + reason));
            default -> new RegistrationOutcome(membership.externalId(), RegistrationOutcome.Status.PENDING, null);
        };
    }

    /** Step 1: the draft — or, under the same key, the one an earlier attempt created. */
    private Draft create(String externalId, String did, Map<String, Object> request) {
        var created = call("create the onboarding request", () -> restClient.post()
                .uri("")
                .headers(headers -> {
                    authorize(headers, did);
                    headers.set(IDEMPOTENCY_KEY_HEADER, "dx-create-" + externalId);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(Draft.class));
        var draft = created.getBody();
        if (draft == null || draft.id() == null) {
            throw new IllegalStateException("The DECADE-X onboarding API returned no request id");
        }
        return draft;
    }

    /**
     * Step 2: every placeholder the draft does not hold yet. A slot already taken means an earlier
     * upload went through after all; a version conflict, that the draft changed since it was read:
     * read again and carry on. Returns the draft's version after the last one.
     */
    private String addDocuments(String requestId, String did, List<Placeholder> placeholders) {
        var documents = documents(requestId, did);
        var version = documents.version();
        for (var placeholder : placeholders) {
            if (documents.holds(placeholder)) {
                continue;
            }
            try {
                version = upload(requestId, did, version, placeholder);
            } catch (Refusal refusal) {
                if (!refusal.is("document-slot-taken") && !refusal.is("version-conflict")) {
                    throw refusal;
                }
                log.info("DECADE-X onboarding request '{}': adding the {} was refused ({}), reading the draft again",
                        requestId, placeholder.describe(), refusal.problem().type());
                documents = documents(requestId, did);
                version = documents.holds(placeholder) ? documents.version()
                        : upload(requestId, did, documents.version(), placeholder);
            }
        }
        return version;
    }

    /** Step 3: the submit, under the version the documents left the draft at. */
    private RequestStatus submit(String externalId, String did, String requestId, String version) {
        var submitted = call("submit the onboarding request", () -> restClient.post()
                .uri("/{id}/submit", requestId)
                .headers(headers -> {
                    authorize(headers, did);
                    headers.setIfMatch(version);
                    headers.set(IDEMPOTENCY_KEY_HEADER, "dx-submit-" + externalId);
                })
                .retrieve()
                .body(RequestStatus.class));
        if (submitted == null || submitted.status() == null) {
            throw new IllegalStateException("The DECADE-X onboarding API returned no status for the submitted request");
        }
        return submitted;
    }

    private Documents documents(String requestId, String did) {
        var listed = call("read the documents of the onboarding request", () -> restClient.get()
                .uri("/{id}/documents", requestId)
                .headers(headers -> authorize(headers, did))
                .retrieve()
                .toEntity(DocumentList.class));
        var body = listed.getBody();
        return new Documents(listed.getHeaders().getETag(), body == null ? List.of() : body.documents());
    }

    /** Adds one document; returns the draft's new version. */
    private String upload(String requestId, String did, String version, Placeholder placeholder) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("type", placeholder.type());
        if (placeholder.useCaseId() != null) {
            parts.add("useCaseId", placeholder.useCaseId());
        }
        parts.add("file", pdf(placeholder.filename(), placeholder.content()));
        var added = call("add the " + placeholder.describe(), () -> restClient.post()
                .uri("/{id}/documents", requestId)
                .headers(headers -> {
                    authorize(headers, did);
                    headers.setIfMatch(version);
                })
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toBodilessEntity());
        return added.getHeaders().getETag();
    }

    /**
     * Deletes a draft that will not be submitted, so it does not count against the participant's
     * drafts. Best effort: a draft left behind is purged by the TSP in time.
     */
    private void discard(String requestId, String did) {
        try {
            var version = restClient.get()
                    .uri("/{id}", requestId)
                    .headers(headers -> authorize(headers, did))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders().getETag();
            restClient.delete()
                    .uri("/{id}", requestId)
                    .headers(headers -> {
                        authorize(headers, did);
                        headers.setIfMatch(version);
                    })
                    .retrieve()
                    .toBodilessEntity();
            log.info("DECADE-X onboarding request '{}' was not submitted and has been deleted", requestId);
        } catch (RuntimeException e) {
            log.warn("DECADE-X onboarding request '{}' was not submitted and could not be deleted: {}", requestId,
                    e.getMessage());
        }
    }

    /**
     * One call to the TSP, retried once when the TSP could not be reached or its gateway failed
     * (502, 503); a refusal comes back as a {@link Refusal}.
     */
    private <T> T call(String step, Supplier<T> call) {
        try {
            try {
                return call.get();
            } catch (ResourceAccessException | HttpServerErrorException.BadGateway
                     | HttpServerErrorException.ServiceUnavailable e) {
                log.warn("DECADE-X onboarding API: could not {} ({}), trying once more", step, e.getMessage());
                return call.get();
            }
        } catch (RestClientResponseException e) {
            throw new Refusal(step, e, problem(e));
        }
    }

    /** The problem the TSP answered with; empty when the body is none. */
    private Problem problem(RestClientResponseException e) {
        var contentType = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType();
        if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)) {
            return null;
        }
        try {
            return objectMapper.readValue(e.getResponseBodyAsByteArray(), Problem.class);
        } catch (IOException unreadable) {
            log.debug("DECADE-X onboarding API: unreadable problem body", unreadable);
            return null;
        }
    }

    private static HttpEntity<Object> part(Object body, MediaType contentType) {
        var headers = new HttpHeaders();
        headers.setContentType(contentType);
        return new HttpEntity<>(body, headers);
    }

    /** A file part: the multipart writer takes the part's filename from the resource. */
    private static HttpEntity<Object> pdf(String filename, byte[] content) {
        return part(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, MediaType.APPLICATION_PDF);
    }

    private void authorize(HttpHeaders headers, String did) {
        headers.setBearerAuth(credentials.getToken());
        headers.set(CONNECTOR_ID_HEADER, did);
    }

    /** The registration as the TSP's request data, completed with what the hub owns. */
    private Map<String, Object> request(String externalId, String did, MemberData data) {
        var request = objectMapper.convertValue(data.registration(), new TypeReference<LinkedHashMap<String, Object>>() {
        });
        var legalEntity = objectMapper.convertValue(request.get("legalEntity"),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        legalEntity.put("legalName", data.name());
        legalEntity.put("preferredDid", did);
        if (data.hostedHere() && data.memberId() != null) {
            // VE extension: the DECADE-X-ID the hub generated for a member it hosts, which its
            // deployment was provisioned with
            legalEntity.put("legalEntityId", data.memberId());
        }
        request.put("legalEntity", legalEntity);
        if (request.get("ucas") instanceof List<?> ucas) {
            // the TSP names the use case itself
            request.put("ucas", ucas.stream().map(uca -> {
                var agreement = objectMapper.convertValue(uca, new TypeReference<LinkedHashMap<String, Object>>() {
                });
                agreement.remove("useCaseName");
                return agreement;
            }).toList());
        }
        request.put("applicantReference", externalId);
        return request;
    }

    /** The documents the TSP requires: the signed GTC, and a signed UCA per use case. */
    private static List<Placeholder> placeholders(DecadeXRegistration registration, String legalName) {
        var placeholders = new ArrayList<Placeholder>();
        placeholders.add(new Placeholder("gtc", null, "gtc-placeholder.pdf",
                PlaceholderDocuments.gtc(registration.gtc().versionNumber(), legalName)));
        for (var uca : registration.ucas()) {
            var useCaseId = uca.useCaseId().id();
            placeholders.add(new Placeholder("uca", useCaseId, "uca-" + useCaseId + "-placeholder.pdf",
                    PlaceholderDocuments.uca(useCaseId, uca.versionNumber(), legalName)));
        }
        return placeholders;
    }

    private DecadeXRegistration registration(MemberData data) {
        return validator.read(data.registration(), DecadeXRegistration.class, DATASPACE);
    }

    private record Placeholder(String type, String useCaseId, String filename, byte[] content) {

        String describe() {
            return useCaseId == null ? type + " document" : "%s document for '%s'".formatted(type, useCaseId);
        }
    }

    /** The documents on file, and the version they were read at (the response's {@code ETag}). */
    private record Documents(String version, List<Document> documents) {

        boolean holds(Placeholder placeholder) {
            return documents.stream().anyMatch(document -> placeholder.type().equals(document.type())
                    && (placeholder.useCaseId() == null || placeholder.useCaseId().equals(document.useCaseId())));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Draft(String id, String businessId, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentList(List<Document> documents) {

        DocumentList {
            documents = documents == null ? List.of() : documents;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Document(String documentId, String type, String useCaseId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RequestStatus(String status, LegalEntity legalEntity, Decision decision) {
    }

    /** @param legalEntityId the participant's DECADE-X-ID, once approved */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LegalEntity(String legalEntityId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Decision(String rejectReasonCode, String rejectComment) {

        String describe() {
            if (rejectComment == null || rejectComment.isBlank()) {
                return rejectReasonCode;
            }
            return rejectReasonCode == null ? rejectComment : rejectReasonCode + ": " + rejectComment;
        }
    }

    /** An RFC 9457 problem, as the TSP refuses a call with one. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Problem(String type, String title, Integer status, String detail, List<FieldError> errors,
                   String documentId) {

        static final String TYPE_PREFIX = "urn:decade-x:tsp:problem:";

        Problem {
            errors = errors == null ? List.of() : errors;
        }

        boolean is(String code) {
            return (TYPE_PREFIX + code).equals(type);
        }

        /** Every refused field, or what the problem says when it names none. */
        String describe() {
            if (!errors.isEmpty()) {
                return errors.stream().map(error -> error.field() + " " + error.message())
                        .collect(Collectors.joining("; "));
            }
            return detail != null ? detail : title;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FieldError(String field, String message) {
    }

    /** The TSP refused a call: its status, and the problem it said why with (null when none). */
    private static class Refusal extends RuntimeException {

        private final transient Problem problem;

        Refusal(String step, RestClientResponseException cause, Problem problem) {
            super("The DECADE-X onboarding API refused to %s (%s): %s".formatted(step, cause.getStatusCode().value(),
                    problem != null ? problem.describe() : cause.getResponseBodyAsString()), cause);
            this.problem = problem;
        }

        Problem problem() {
            return problem;
        }

        boolean is(String code) {
            return problem != null && problem.is(code);
        }

        /**
         * The TSP's verdict on the registration is the caller's to fix — so it is an invalid
         * registration, naming every refused field; anything else (a quota, an unexpected state)
         * is this hub's or the TSP's problem.
         */
        RuntimeException translated() {
            if (is("invalid-request") || is("onboarding-request-submit-rejected")) {
                return new InvalidRegistrationException("Invalid %s registration, refused by the TSP: %s"
                        .formatted(DATASPACE, problem.describe()));
            }
            return new IllegalStateException(getMessage(), getCause());
        }
    }
}
