package com.metaform.cxve.hub.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.metaform.cxve.hub.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Minimal client for the Membership Hub through the VE gateway ({@code /hub}) — the full member
 * journey: {@code POST /api/members} into a dataspace deploys the participant profile
 * (CFM Tenant Manager) and submits the registration to that dataspace's onboarding API;
 * {@code GET /api/members/<externalId>} reads the correlated record, refreshing the provisioning
 * state from the Tenant Manager on every call.
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
     * {@code externalId}. The hub answers as soon as the first leg is under way (PROVISIONING for
     * a member it hosts, which it deploys before registering); the rest lands asynchronously —
     * poll {@link #awaitProvisioned}. A membership already dead on arrival fails here.
     */
    public JsonNode onboard(E2eDataspace dataspace, String name, String shortName, String vatId) {
        // The common fields plus the dataspace's own registration object. The member id is given
        // only where the dataspace has its members declare it (Catena-X: the BPN); DECADE-X
        // refuses one — the TSP assigns it, and the hub generates one for a member it hosts.
        var request = mapper.createObjectNode();
        request.put("dataspace", dataspace.id());
        request.put("name", name);
        request.put("shortName", shortName);
        var memberId = dataspace.memberIdFor(shortName);
        if (memberId != null) {
            request.put("memberId", memberId);
        }
        request.set("registration", json(dataspace.registration(vatId)));
        var response = given()
                .baseUri(baseUrl)
                .contentType(ContentType.JSON)
                .body(request.toString())
                .post("/api/members");
        assertThat(response.statusCode())
                .withFailMessage("membership submission for '%s' failed: %s", name, response.asString())
                .isEqualTo(201);
        var membership = json(response.asString());
        failOnDeadEnd(membership);
        log("membership submitted to %s: \"%s\" (externalId=%s, state=%s)",
                dataspace.id(), name, membership.path("externalId").asText(), membership.path("state").asText());
        return membership;
    }

    /**
     * Polls the membership until it is CREDENTIALS_OFFERED and returns the record — the
     * participant context id is on it. That is the terminal state of every member: the hub deploys
     * the participant's resources (when it hosts them) and then registers it, and the registration
     * ends with the issuer offering it the membership credentials, which its wallet requests over
     * DCP. Waiting for PROVISIONED instead would hand back a participant that is not even
     * registered yet, let alone holding credentials, and every DSP message it then sends would be
     * rejected.
     *
     * <p>REJECTED, FAILED and REGISTERING (a registration that did not confirm within the
     * submission — the hub never provisions such a record) fail immediately rather than timing out.
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
                    .withFailMessage("membership %s has not been offered its credentials yet: %s",
                            externalId, membership)
                    .isEqualTo("CREDENTIALS_OFFERED");
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
