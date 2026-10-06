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
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * DECADE-X onboarding through the TSP's onboarding intake (the VE's dx-onboarding-api): the hub
 * submits the member's onboarding request — the {@code registration} object, completed with the
 * member's legal name, DID and the hub's external id as {@code applicantReference} — together with
 * placeholder GTC and UCA documents, as one multipart request.
 *
 * <p>The TSP has no status callbacks: the hub {@linkplain #pollsStatus() polls} the request's
 * status instead. {@code APPROVED} maps to the hub's CONFIRMED — like Catena-X's confirmation, it
 * means the TSP registered the participant as credential holder and had the issuer offer it the
 * DecadeXMembershipCredential — and carries the participant's DECADE-X-ID; {@code REJECTED} and
 * {@code APPROVAL_FAILED} (the provisioning after an approval failed, which nothing retries) map to
 * DECLINED, and every other status — the steps of the review — to PENDING.
 *
 * <p>The DECADE-X-ID is declared with the registration, as a Catena-X member declares its BPN, and
 * sent along as {@code legalEntity.legalEntityId} — a VE extension of the TSP's request, which the
 * TSP honors. A member hosted HERE must declare it: its deployment needs it before any registration
 * (certo reads it). An EXTERNAL member may: one that declares none is assigned one by the TSP on
 * approval, and the confirmation hands it to the hub.
 *
 * <p>In the dataspace, a participant reaches the TSP as an asset of the federated connector, whose
 * data plane stamps the calling connector's identity on every call. The hub calls the TSP directly
 * and stands in for that data plane: it sends the member's DID as the identity, in the header the
 * TSP is ASSUMED to read it from ({@value #CONNECTOR_ID_HEADER} — the TSP's specification does not
 * say how the identity reaches it). Requests are scoped to that identity, so the status is read
 * under the same one.
 */
public class DecadeXOnboarding implements DataspaceOnboarding {

    public static final String DATASPACE = "decade-x";

    static final String CONNECTOR_ID_HEADER = "X-Connector-Id";
    static final String DECADE_X_ID = "DX-[0-9]{8}";
    static final String REQUESTS_PATH = "/api/v1/onboarding-requests";

    private static final Logger log = LoggerFactory.getLogger(DecadeXOnboarding.class);

    // A plain mapper, like the RegistrationValidator's: the registration's shape is independent of
    // the web layer's JSON configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final ClientCredentials credentials;
    private final RegistrationValidator validator;

    public DecadeXOnboarding(DataspaceProperties.Onboarding onboarding, RegistrationValidator validator) {
        this(RestClient.builder(), onboarding, new ClientCredentials(onboarding.auth()), validator);
    }

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
     * Besides the registration object: a declared DECADE-X-ID must be well-formed, and a member
     * hosted here must declare one (its deployment needs it); an external member may leave it to
     * the TSP to assign.
     */
    @Override
    public void validate(MemberData data) {
        var memberId = data.memberId() == null || data.memberId().isBlank() ? null : data.memberId();
        if (memberId == null && data.hostedHere()) {
            throw new InvalidRegistrationException(
                    "memberId must be a DECADE-X-ID (DX- and 8 digits): a member hosted here declares its own");
        }
        if (memberId != null && !memberId.matches(DECADE_X_ID)) {
            throw new InvalidRegistrationException("memberId must be a DECADE-X-ID (DX- and 8 digits)");
        }
        registration(data);
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
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("request", part(request(externalId, did, data), MediaType.APPLICATION_JSON));
        parts.add("gtcDocument", pdf("gtc-placeholder.pdf",
                PlaceholderDocuments.gtc(registration.gtc().versionNumber(), data.name())));
        for (var uca : registration.ucas()) {
            parts.add("ucaDocument[" + uca.useCaseId() + "]", pdf("uca-" + uca.useCaseId() + "-placeholder.pdf",
                    PlaceholderDocuments.uca(uca.useCaseId(), uca.versionNumber(), data.name())));
        }
        var receipt = restClient.post()
                .uri(REQUESTS_PATH)
                .headers(headers -> authorize(headers, did))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(Receipt.class);
        if (receipt == null || receipt.id() == null) {
            throw new IllegalStateException("The DECADE-X onboarding API returned no request id");
        }
        log.info("DECADE-X onboarding request '{}' ({}) submitted for '{}': {}", receipt.id(), receipt.businessId(),
                externalId, receipt.status());
        return receipt.id();
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
                .uri(REQUESTS_PATH + "/{id}", membership.onboardingProcessId())
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

    /** The registration as the TSP's {@code request} part, completed with what the hub owns. */
    private Map<String, Object> request(String externalId, String did, MemberData data) {
        var request = objectMapper.convertValue(data.registration(), new TypeReference<LinkedHashMap<String, Object>>() {
        });
        var legalEntity = objectMapper.convertValue(request.get("legalEntity"),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        legalEntity.put("legalName", data.name());
        legalEntity.put("preferredDid", did);
        if (data.memberId() != null && !data.memberId().isBlank()) {
            // VE extension: the declared DECADE-X-ID (a hosted member's deployment was provisioned with it)
            legalEntity.put("legalEntityId", data.memberId());
        }
        request.put("legalEntity", legalEntity);
        request.put("applicantReference", externalId);
        return request;
    }

    private DecadeXRegistration registration(MemberData data) {
        return validator.read(data.registration(), DecadeXRegistration.class, DATASPACE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Receipt(String id, String businessId, String status) {
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
}
