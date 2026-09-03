package com.metaform.cxve.hub.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.metaform.cxve.hub.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Minimal client for the EDC controlplane management API through the VE gateway
 * ({@code http://<host>/api/management/v5}). Every call carries the jwtlet bearer token —
 * clearglass enforces the route→scope map, the controlplane validates the token again. A token
 * obtained with {@code resource=issuer, scope=admin} may operate on any participant context
 * (management-api:admin covers cross-context access).
 */
public class ManagementApi {

    static final String MANAGEMENT_CONTEXT = "https://w3id.org/edc/connector/management/v2";
    static final String CX_POLICY_CONTEXT = "https://w3id.org/catenax/2025/9/policy/context.jsonld";

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();

    public ManagementApi(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public Response post(String path, String body) {
        return given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body)
                .post(path);
    }

    public Response get(String path) {
        return given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer " + token)
                .get(path);
    }

    /**
     * Creates a runtime-level CEL expression backing a policy constraint: any ODRL constraint
     * whose leftOperand equals {@code leftOperand} is evaluated by {@code expression}. Scoped to
     * catalog + negotiation + transfer, no action restriction (applies to access and use
     * policies alike). A true upsert: on 409 the stale expression from an earlier run is
     * deleted and re-created, so leftOperand/expression changes actually take effect (a
     * tolerated 409 would silently keep the OLD binding and fail policy creation later).
     */
    public void upsertCelExpression(String id, String leftOperand, String expression) {
        var body = """
                {
                  "@context": ["%s"],
                  "@type": "CelExpression",
                  "@id": "%s",
                  "leftOperand": "%s",
                  "scopes": ["catalog", "contract.negotiation", "transfer.process"],
                  "description": "Expression to check for the presence of a %s",
                  "expression": "%s"
                }""".formatted(MANAGEMENT_CONTEXT, id, leftOperand, leftOperand, expression.replace("\"", "\\\""));
        var status = post("/celexpressions", body).statusCode();
        if (status == 409) {
            log("   Management API:  CEL expression '%s' exists from an earlier run — replacing it", id);
            var deleteStatus = given()
                    .baseUri(baseUrl)
                    .header("Authorization", "Bearer " + token)
                    .delete("/celexpressions/%s".formatted(id))
                    .statusCode();
            assertThat(deleteStatus)
                    .withFailMessage("deleting stale CEL expression '%s' failed with HTTP %d", id, deleteStatus)
                    .matches(s -> s < 300 || s == 404);
            status = post("/celexpressions", body).statusCode();
        }
        assertThat(status)
                .withFailMessage("creating CEL expression '%s' failed with HTTP %d", id, status)
                .isBetween(200, 299);
        log("   Management API:  CEL expression '%s' ready (leftOperand %s)", id, leftOperand);
    }

    public void createAsset(String pcid, String assetId, String dataUrl) {
        createAsset(pcid, assetId, dataUrl, Map.of());
    }

    /**
     * Asset with dataplane-metadata properties. On every data flow for this asset, Siglet copies
     * these properties verbatim into the claims of the flow token it mints (the flow's metadata
     * comes from the PROVIDER-side transfer process, which carries the asset's
     * dataplaneMetadata) — the way to stamp e.g. the {@code bpn} claim Certo requires into the
     * counterparty's token. A PLAIN properties object, verified against the deployed
     * controlplane: it stores the keys raw ("bpn" stays "bpn" in edc_asset.dataplane_metadata,
     * no vocab expansion), while the {@code "@type": "@json"} literal form silently drops the
     * properties altogether.
     */
    public void createAsset(String pcid, String assetId, String dataUrl, Map<String, String> dataplaneProperties) {
        var metadata = "";
        if (!dataplaneProperties.isEmpty()) {
            var props = mapper.createObjectNode();
            dataplaneProperties.forEach(props::put);
            metadata = """
                    ,
                      "dataplaneMetadata": {
                        "@type": "DataplaneMetadata",
                        "properties": %s
                      }""".formatted(props.toString());
        }
        var body = """
                {
                  "@context": ["%s"],
                  "@type": "Asset",
                  "@id": "%s",
                  "properties": {"name": "cxve e2e asset"},
                  "dataAddress": {"@type": "DataAddress", "type": "HttpData", "baseUrl": "%s"}%s
                }""".formatted(MANAGEMENT_CONTEXT, assetId, dataUrl, metadata);
        expect2xx(post("/participants/%s/assets".formatted(pcid), body), "asset " + assetId);
        if (!dataplaneProperties.isEmpty()) {
            verifyDataplaneProperties(pcid, assetId, dataplaneProperties);
        }
        log("   Management API:  asset '%s' created in participant context %s", assetId, pcid);
    }

    /**
     * Reads the asset back and asserts the dataplane-metadata property keys survived JSON-LD
     * processing unexpanded — if the deployed controlplane handles the {@code @json} literal
     * differently and the keys come back as IRIs, the flow tokens would carry wrong claim names
     * and every CCM call would fail with 401 much later; fail fast here instead.
     */
    private void verifyDataplaneProperties(String pcid, String assetId, Map<String, String> expected) {
        var response = get("/participants/%s/assets/%s".formatted(pcid, assetId));
        assertThat(response.statusCode()).isEqualTo(200);
        try {
            var stored = mapper.readTree(response.asString()).path("dataplaneMetadata").path("properties");
            expected.forEach((key, value) -> assertThat(stored.path(key).asText())
                    .withFailMessage("asset '%s': dataplaneMetadata property '%s' did not survive JSON-LD "
                                     + "round-tripping (stored: %s) — flow tokens would carry wrong claim names", assetId, key, stored)
                    .isEqualTo(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Policy whose constraints reference the given leftOperand IRIs (each backed by a CEL
     * expression). The rightOperand is a placeholder — the e2e CEL expressions check fixed
     * credential claims and ignore it.
     */
    public void createPolicy(String pcid, String policyId, String action, List<Constraint> constraints) {
        var policyConstraints = mapper.createArrayNode();
        constraints.forEach(op -> {
            var c = mapper.createObjectNode();
            c.put("leftOperand", op.leftOperand);
            c.put("operator", op.operator);
            c.put("rightOperand", op.rightOperand);
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
                }""".formatted(MANAGEMENT_CONTEXT, CX_POLICY_CONTEXT,  policyId, action, and.toString());
        expect2xx(post("/participants/%s/policydefinitions".formatted(pcid), body), "policy " + policyId);
        log("   Management API:  policy '%s' (%d credential constraints) created", policyId, constraints.size());
    }

    public void createContractDefinition(String pcid, String id, String accessPolicyId, String contractPolicyId) {
        var body = """
                {
                  "@context": ["%s"],
                  "@type": "ContractDefinition",
                  "@id": "%s",
                  "accessPolicyId": "%s",
                  "contractPolicyId": "%s",
                  "assetsSelector": []
                }""".formatted(MANAGEMENT_CONTEXT, id, accessPolicyId, contractPolicyId);
        expect2xx(post("/participants/%s/contractdefinitions".formatted(pcid), body), "contract definition " + id);
        log("   Management API:  contract definition '%s' created (access=%s, contract=%s)", id, accessPolicyId, contractPolicyId);
    }

    /** The catalog dataset's offer for {@code assetId} plus the catalog's own JSON-LD context. */
    public record CatalogOffer(JsonNode offer, JsonNode catalogContext) {
    }

    /**
     * Polls the counterparty catalog until the dataset for {@code assetId} shows up (a fresh
     * contract definition can take a moment; a PERSISTENTLY absent dataset means the consumer
     * does not satisfy the ACCESS policy — check credentials/CEL — or the offer was never
     * seeded). Fails after the timeout.
     */
    public CatalogOffer awaitCatalogOffer(String consumerPcid, String providerDsp, String providerDid,
                                          String assetId, Duration timeout) {
        var request = """
                {
                  "@context": ["%s"],
                  "@type": "CatalogRequest",
                  "counterPartyAddress": "%s",
                  "counterPartyId": "%s",
                  "protocol": "cx-neptune"
                }""".formatted(MANAGEMENT_CONTEXT, providerDsp, providerDid);
        log("   Management API:  requesting provider catalog as consumer %s, waiting for dataset '%s'...", consumerPcid, assetId);
        var result = new AtomicReference<CatalogOffer>();
        await().atMost(timeout).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = post("/participants/%s/catalog/request".formatted(consumerPcid), request);
            assertThat(response.statusCode()).isEqualTo(200);
            var catalog = mapper.readTree(response.asString());
            var dataset = findByAtId(catalog.path("dataset"), assetId);
            assertThat(dataset)
                    .withFailMessage("dataset '%s' not (yet) in the provider catalog", assetId)
                    .isNotNull();
            var offer = first(dataset.path("hasPolicy"));
            assertThat(offer).withFailMessage("dataset '%s' carries no offer", assetId).isNotNull();
            result.set(new CatalogOffer(offer, catalog.path("@context")));
        });
        log("   Management API:  catalog offer found for '%s' (offer id %s)", assetId, result.get().offer().path("@id").asText());
        return result.get();
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
        log("   Management API:  contract negotiation started: %s", id);
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
        log("   Management API:  transfer process started: %s", id);
        return id;
    }

    /**
     * Polls a state-bearing resource until it reaches one of {@code acceptable}; aborts
     * immediately on TERMINATED (a state machine never leaves it).
     */
    public JsonNode awaitState(String path, Duration timeout, Set<String> acceptable) {
        log("   Management API:  waiting for %s to reach %s...", path, acceptable);
        var result = new AtomicReference<JsonNode>();
        await().atMost(timeout).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var response = get(path);
            assertThat(response.statusCode()).isEqualTo(200);
            var node = mapper.readTree(response.asString());
            var state = node.path("state").asText();
            if ("TERMINATED".equals(state)) {
                // not an AssertionError: awaitility must NOT retry a terminal failure
                throw new IllegalStateException("%s reached TERMINATED: %s".formatted(path, node));
            }
            assertThat(state).isIn(acceptable);
            result.set(node);
        });
        log("   Management API:  %s reached state %s", path, result.get().path("state").asText());
        return result.get();
    }

    private String idOf(Response response) {
        try {
            return mapper.readTree(response.asString()).path("@id").asText();
        } catch (Exception e) {
            throw new IllegalStateException("no @id in response: " + response.asString(), e);
        }
    }

    private static void expect2xx(Response response, String what) {
        assertThat(response.statusCode())
                .withFailMessage("creating %s failed with HTTP %d: %s", what, response.statusCode(), response.asString())
                .isBetween(200, 299);
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
