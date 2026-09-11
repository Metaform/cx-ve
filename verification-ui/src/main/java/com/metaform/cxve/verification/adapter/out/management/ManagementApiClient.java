package com.metaform.cxve.verification.adapter.out.management;

import com.metaform.cxve.verification.adapter.out.auth.TokenProvider;
import com.metaform.cxve.verification.adapter.out.http.HttpResult;
import com.metaform.cxve.verification.adapter.out.http.RestCalls;
import com.metaform.cxve.verification.application.Poller;
import com.metaform.cxve.verification.application.VerificationException;
import com.metaform.cxve.verification.config.VerificationProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Client for the EDC controlplane management API, ported from the e2e suite's
 * {@code ManagementApi}: the JSON-LD bodies are kept byte-identical (they encode hard-won
 * knowledge about contexts, verbatim offer copying and object-vs-array compaction), only the
 * transport (RestClient), token sourcing (jwtlet workload exchange per call — runs take minutes,
 * tokens expire) and wait loops ({@link Poller}) differ. A token exchanged under
 * {@code resource=issuer, scope=admin} may operate on any participant context.
 *
 * <p>The create methods are idempotent by design — the verification participant's permanent
 * offer is re-seeded on every ensure: GET first, create on absence, and a concurrent 409 counts
 * as created.
 */
@Component
public class ManagementApiClient {

    public static final String MANAGEMENT_CONTEXT = "https://w3id.org/edc/connector/management/v2";
    public static final String CX_POLICY_CONTEXT = "https://w3id.org/catenax/2025/9/policy/context.jsonld";

    public static final Constraint MEMBERSHIP_CONSTRAINT = new Constraint("Membership", "eq", "active");
    public static final Constraint FRAMEWORK_AGREEMENT_CONSTRAINT = new Constraint("FrameworkAgreement", "eq", "DataExchangeGovernance:1.0");
    public static final Constraint USAGE_PURPOSE_CONSTRAINT = new Constraint("UsagePurpose", "isAnyOf", "cx.pcf.base:1");
    public static final Constraint DATA_USAGE_DEFINITION_CONSTRAINT = new Constraint("DataUsageEndDefinition", "eq", "cx.dataUsageEnd.unlimited:1");

    private static final Logger log = LoggerFactory.getLogger(ManagementApiClient.class);

    private final RestClient managementRestClient;
    private final TokenProvider tokenProvider;
    private final VerificationProperties properties;
    private final ObjectMapper mapper;

    public ManagementApiClient(@Qualifier("managementRestClient") RestClient managementRestClient,
                               TokenProvider tokenProvider,
                               VerificationProperties properties,
                               ObjectMapper mapper) {
        this.managementRestClient = managementRestClient;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** Asset fronting Certo's protocol API behind the CCM transfer type. */
    public void createAssetIdempotent(String pcid, String assetId, String dataUrl) {
        var body = """
                {
                  "@context": ["%s"],
                  "@type": "Asset",
                  "@id": "%s",
                  "properties": {"name": "cxve verification asset"},
                  "dataAddress": {"@type": "DataAddress", "type": "HttpData", "baseUrl": "%s"}
                }""".formatted(MANAGEMENT_CONTEXT, assetId, dataUrl);
        createIdempotent("asset " + assetId,
                "/participants/%s/assets/%s".formatted(pcid, assetId),
                "/participants/%s/assets".formatted(pcid), body);
    }

    /**
     * Policy whose constraints reference the given leftOperand IRIs (each backed by a CEL
     * expression seeded by the deployed catenax-profile). The rightOperand is a placeholder —
     * the CEL expressions check fixed credential claims and ignore it.
     */
    public void createPolicyIdempotent(String pcid, String policyId, String action, List<Constraint> constraints) {
        var policyConstraints = mapper.createArrayNode();
        constraints.forEach(op -> {
            var c = mapper.createObjectNode();
            c.put("leftOperand", op.leftOperand());
            c.put("operator", op.operator());
            c.put("rightOperand", op.rightOperand());
            policyConstraints.add(c);
        });
        var and = mapper.createObjectNode().set("and", policyConstraints);
        var body = """
                {
                  "@context": ["%s", "%s"],
                  "@type": "PolicyDefinition",
                  "@id": "%s",
                  "policy": {
                    "@type": "Set",
                    "permission": [{
                      "action": "%s",
                      "constraint": [%s]
                    }]
                  }
                }""".formatted(MANAGEMENT_CONTEXT, CX_POLICY_CONTEXT, policyId, action, and.toString());
        createIdempotent("policy " + policyId,
                "/participants/%s/policydefinitions/%s".formatted(pcid, policyId),
                "/participants/%s/policydefinitions".formatted(pcid), body);
    }

    public void createContractDefinitionIdempotent(String pcid, String id, String accessPolicyId, String contractPolicyId) {
        var body = """
                {
                  "@context": ["%s"],
                  "@type": "ContractDefinition",
                  "@id": "%s",
                  "accessPolicyId": "%s",
                  "contractPolicyId": "%s",
                  "assetsSelector": []
                }""".formatted(MANAGEMENT_CONTEXT, id, accessPolicyId, contractPolicyId);
        createIdempotent("contract definition " + id,
                "/participants/%s/contractdefinitions/%s".formatted(pcid, id),
                "/participants/%s/contractdefinitions".formatted(pcid), body);
    }

    /** The catalog dataset's offer for {@code assetId} plus the catalog's own JSON-LD context. */
    public record CatalogOffer(JsonNode offer, JsonNode catalogContext) {
    }

    /**
     * Polls the counterparty catalog until the dataset for {@code assetId} shows up (a fresh
     * contract definition can take a moment; a PERSISTENTLY absent dataset means the consumer
     * does not satisfy the ACCESS policy — check credentials — or the offer was never seeded).
     */
    public CatalogOffer awaitCatalogOffer(String consumerPcid, String providerDsp, String providerDid, String assetId) {
        var request = """
                {
                  "@context": ["%s"],
                  "@type": "CatalogRequest",
                  "counterPartyAddress": "%s",
                  "counterPartyId": "%s",
                  "protocol": "cx-neptune"
                }""".formatted(MANAGEMENT_CONTEXT, providerDsp, providerDid);
        log.info("requesting provider catalog as consumer {}, waiting for dataset '{}'", consumerPcid, assetId);
        var result = Poller.poll("dataset '%s' in the provider catalog".formatted(assetId),
                properties.timeouts().catalog(), properties.pollInterval(), () -> {
                    var response = postPolled("/participants/%s/catalog/request".formatted(consumerPcid), request,
                            "catalog request");
                    if (response.status() != 200) {
                        throw new Poller.RetryException("catalog request failed with HTTP %d: %s"
                                .formatted(response.status(), response.body()));
                    }
                    var catalog = json(response.body());
                    var dataset = findByAtId(catalog.path("dataset"), assetId);
                    if (dataset == null) {
                        throw new Poller.RetryException("dataset '%s' not (yet) in the provider catalog".formatted(assetId));
                    }
                    var offer = first(dataset.path("hasPolicy"));
                    if (offer == null) {
                        throw new Poller.RetryException("dataset '%s' carries no offer".formatted(assetId));
                    }
                    return new CatalogOffer(offer, catalog.path("@context"));
                });
        log.info("catalog offer found for '{}' (offer id {})", assetId, result.offer().path("@id").asText());
        return result;
    }

    /**
     * Starts a contract negotiation mirroring the catalog offer verbatim (under the catalog's
     * own JSON-LD context, so compacted terms expand to the same IRIs) plus assigner/target.
     * Returns the negotiation id.
     */
    public String startNegotiation(String consumerPcid, String providerDsp, String providerDid,
                                   String assetId, CatalogOffer catalogOffer) {
        var policy = (ObjectNode) catalogOffer.offer().deepCopy();
        policy.put("assigner", providerDid);
        policy.put("target", assetId);

        var context = mapper.createArrayNode().add(MANAGEMENT_CONTEXT);
        appendContext(context, catalogOffer.catalogContext());

        var body = mapper.createObjectNode();
        body.set("@context", context);
        body.put("@type", "ContractRequest");
        body.put("counterPartyAddress", providerDsp);
        body.put("protocol", "cx-neptune");
        body.set("policy", policy);

        var response = post("/participants/%s/contractnegotiations".formatted(consumerPcid), body.toString());
        expect2xx(response, "contract negotiation");
        var id = idOf(response);
        log.info("contract negotiation started: {}", id);
        return id;
    }

    public String startTransfer(String consumerPcid, String agreementId, String providerDsp, String transferType) {
        var body = """
                {
                  "@context": ["%s"],
                  "@type": "TransferRequest",
                  "contractId": "%s",
                  "counterPartyAddress": "%s",
                  "protocol": "cx-neptune",
                  "transferType": "%s"
                }""".formatted(MANAGEMENT_CONTEXT, agreementId, providerDsp, transferType);
        var response = post("/participants/%s/transferprocesses".formatted(consumerPcid), body);
        expect2xx(response, "transfer process");
        var id = idOf(response);
        log.info("transfer process started: {}", id);
        return id;
    }

    /**
     * Polls a state-bearing resource until it reaches one of {@code acceptable}; aborts
     * immediately on TERMINATED (a state machine never leaves it).
     */
    public JsonNode awaitState(String path, Duration timeout, Set<String> acceptable) {
        log.info("waiting for {} to reach {}", path, acceptable);
        var result = Poller.poll("%s to reach %s".formatted(path, acceptable), timeout, properties.pollInterval(), () -> {
            var response = getPolled(path, "state poll");
            if (response.status() != 200) {
                throw new Poller.RetryException("reading %s failed with HTTP %d: %s"
                        .formatted(path, response.status(), response.body()));
            }
            var node = json(response.body());
            var state = node.path("state").asText();
            if ("TERMINATED".equals(state)) {
                throw new VerificationException("%s reached TERMINATED: %s".formatted(path, node));
            }
            if (!acceptable.contains(state)) {
                throw new Poller.RetryException("%s in state %s".formatted(path, state));
            }
            return node;
        });
        log.info("{} reached state {}", path, result.path("state").asText());
        return result;
    }

    private void createIdempotent(String what, String getPath, String createPath, String body) {
        if (get(getPath).status() == 200) {
            log.info("{} already exists — reusing it", what);
            return;
        }
        var response = post(createPath, body);
        if (response.status() == 409) {
            log.info("{} was created concurrently — reusing it", what);
            return;
        }
        expect2xx(response, what);
        log.info("{} created", what);
    }

    private HttpResult post(String path, String body) {
        return RestCalls.post(managementRestClient, path, token(), body);
    }

    private HttpResult get(String path) {
        return RestCalls.get(managementRestClient, path, token());
    }

    private HttpResult postPolled(String path, String body, String what) {
        try {
            return post(path, body);
        } catch (ResourceAccessException e) {
            throw new Poller.RetryException(what + ": management API unreachable: " + e.getMessage(), e);
        }
    }

    private HttpResult getPolled(String path, String what) {
        try {
            return get(path);
        } catch (ResourceAccessException e) {
            throw new Poller.RetryException(what + ": management API unreachable: " + e.getMessage(), e);
        }
    }

    private String token() {
        return tokenProvider.getToken(properties.management().tokenResource(), properties.management().tokenScope());
    }

    private String idOf(HttpResult response) {
        var id = json(response.body()).path("@id").asText();
        if (id.isBlank()) {
            throw new VerificationException("no @id in response: " + response.body());
        }
        return id;
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            throw new VerificationException("unparseable management API response: " + body, e);
        }
    }

    private static void expect2xx(HttpResult response, String what) {
        if (!response.is2xx()) {
            throw new VerificationException("creating %s failed with HTTP %d: %s"
                    .formatted(what, response.status(), response.body()));
        }
    }

    /** dataset/hasPolicy come back as object OR array depending on JSON-LD compaction. */
    private static JsonNode findByAtId(JsonNode node, String id) {
        if (node.isObject()) {
            return id.equals(node.path("@id").asText()) ? node : null;
        }
        for (var child : node) {
            if (id.equals(child.path("@id").asText())) {
                return child;
            }
        }
        return null;
    }

    private static JsonNode first(JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }
        return node.isArray() ? (node.isEmpty() ? null : node.get(0)) : node;
    }

    private static void appendContext(ArrayNode target, JsonNode context) {
        if (context.isArray()) {
            context.forEach(target::add);
        } else if (!context.isMissingNode()) {
            target.add(context);
        }
    }

    public record Constraint(String leftOperand, String operator, String rightOperand) {
    }
}
