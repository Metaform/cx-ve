package com.metaform.cxve.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Minimal client for the CFM Tenant Manager through the VE gateway ({@code /api/tm}, rewritten to
 * the TM's {@code /api/v1alpha1}). Only what the suite needs: resolving the participant context id
 * a DID was provisioned under, via {@code POST /participant-profiles/query} with an
 * {@code identifier = '<did>'} predicate. Requires a jwtlet participant token carrying
 * {@code tenant-manager-api:read} — the onboarding-api ServiceAccount's mapping covers it.
 */
public class TenantManagerApi {

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();

    public TenantManagerApi(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /**
     * The participant context id of the profile provisioned under {@code did}. Polls because the
     * id is stamped into the profile's {@code cfm.vpa.state} asynchronously during provisioning —
     * by the time a CONFIRMED status callback arrives it is normally already there, so the first
     * query usually hits. DIDs are run-unique in this suite, so exactly one profile must match.
     */
    public String awaitParticipantContextId(String did, Duration timeout) {
        var result = new AtomicReference<String>();
        await().atMost(timeout).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            var response = given()
                    .baseUri(baseUrl)
                    .header("Authorization", "Bearer " + token)
                    .contentType(ContentType.JSON)
                    .body("""
                            {"predicate": "identifier = '%s'"}""".formatted(did))
                    .post("/participant-profiles/query");
            assertThat(response.statusCode())
                    .withFailMessage("profile query for %s failed: %s", did, response.asString())
                    .isEqualTo(200);
            var profiles = json(response.asString());
            assertThat(profiles.size())
                    .withFailMessage("expected exactly one participant profile for %s, got: %s", did, profiles)
                    .isEqualTo(1);
            var pcid = profiles.path(0).path("properties").path("cfm.vpa.state").path("participantContextId").asText(null);
            assertThat(pcid)
                    .withFailMessage("profile for %s carries no participantContextId yet", did)
                    .isNotNull();
            result.set(pcid);
        });
        return result.get();
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
