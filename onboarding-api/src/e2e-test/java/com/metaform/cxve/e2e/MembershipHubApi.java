package com.metaform.cxve.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.metaform.cxve.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Minimal client for the Membership Hub through the VE gateway ({@code /hub}) — the full member
 * journey: {@code POST /api/members} runs the CX-0006 registration (Onboarding API) and deploys
 * the participant profile (CFM Tenant Manager); {@code GET /api/members/<externalId>} reads the
 * correlated record, refreshing the provisioning state from the Tenant Manager on every call.
 * The hub's API is unauthenticated (operator surface); the wire shapes are mirrored inline, like
 * everywhere else in this suite.
 */
public class MembershipHubApi {

    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public MembershipHubApi(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Submits the member and returns the created membership record — the hub mints the
     * {@code externalId}. The call is synchronous through registration AND profile deployment,
     * so it may take a while; a rejected or failed membership fails here.
     */
    public JsonNode onboard(String name, String shortName, String bpn, String vatId) {
        var response = given()
                .baseUri(baseUrl)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "%s",
                          "shortName": "%s",
                          "bpn": "%s",
                          "uniqueIds": [ { "type": "VAT_ID", "value": "%s" } ],
                          "companyRoles": [ "ACTIVE_PARTICIPANT" ],
                          "agreements": [ { "agreementId": "Catena-X", "consentStatus": "ACTIVE" } ]
                        }""".formatted(name, shortName, bpn, vatId))
                .post("/api/members");
        assertThat(response.statusCode())
                .withFailMessage("membership submission for '%s' failed: %s", name, response.asString())
                .isEqualTo(201);
        var membership = json(response.asString());
        failOnDeadEnd(membership);
        log("membership submitted: \"%s\" (externalId=%s, state=%s)",
                name, membership.path("externalId").asText(), membership.path("state").asText());
        return membership;
    }

    /**
     * Polls the membership until it is PROVISIONED and returns the record — the participant
     * context id is on it. REJECTED, FAILED and REGISTERING (a registration that did not confirm
     * within the submission — the hub never provisions such a record) fail immediately rather
     * than timing out.
     */
    public JsonNode awaitProvisioned(String externalId, Duration timeout) {
        var result = new AtomicReference<JsonNode>();
        await().atMost(timeout).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            var response = given()
                    .baseUri(baseUrl)
                    .get("/api/members/{externalId}", externalId);
            assertThat(response.statusCode())
                    .withFailMessage("membership lookup for %s failed: %s", externalId, response.asString())
                    .isEqualTo(200);
            var membership = json(response.asString());
            failOnDeadEnd(membership);
            assertThat(membership.path("state").asText())
                    .withFailMessage("membership %s not provisioned yet: %s", externalId, membership)
                    .isEqualTo("PROVISIONED");
            result.set(membership);
        });
        return result.get();
    }

    /** Terminal failures and never-provisioned registrations must not burn the await budget. */
    private static void failOnDeadEnd(JsonNode membership) {
        var state = membership.path("state").asText();
        if (state.equals("REJECTED") || state.equals("FAILED") || state.equals("REGISTERING")) {
            throw new IllegalStateException("membership %s ended as %s: %s".formatted(
                    membership.path("externalId").asText(), state,
                    membership.path("failureReason").asText("no reason recorded")));
        }
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
