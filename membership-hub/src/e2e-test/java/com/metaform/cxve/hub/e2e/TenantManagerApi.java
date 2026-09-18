package com.metaform.cxve.hub.e2e;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.metaform.cxve.hub.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Minimal client for the CFM Tenant Manager through the VE gateway
 * ({@code http://<host>/api/tm}, rewritten to the service's {@code /api/v1alpha1}). Deploys a
 * participant's EDC resources the way the Membership Hub does — one tenant, one participant
 * profile — and waits for the participant context the VPA orchestration assigns.
 *
 * <p>The suite needs this for the ONE participant the hub does not deploy for it: the partner of
 * the direct OSP registration contract test. A registration ends with the issuer pushing a
 * credential offer to the credential service the partner's DID document advertises, so the wallet
 * has to exist BEFORE the registration is submitted — which is exactly why the hub deploys first
 * and registers second, and what the suite plays out by hand here.
 *
 * <p>Tokens are the hub's: an RFC 8693 exchange of the membership-hub ServiceAccount's token
 * under the {@code issuer} mapping, whose scopes carry {@code tenant-manager-api:read/write}.
 */
public class TenantManagerApi {

    private static final Duration DEPLOY_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final String baseUrl;
    private final TokenExchange tokenExchange;

    public TenantManagerApi(String baseUrl, TokenExchange tokenExchange) {
        this.baseUrl = baseUrl;
        this.tokenExchange = tokenExchange;
    }

    /**
     * Creates a tenant, deploys a participant profile into it under {@code did} and returns once
     * the participant context exists — at which point the DID document resolves and advertises the
     * credential service an issuer can push to.
     *
     * <p>The profile is the hub's minus the data-plane mappings: this participant never transfers
     * anything, it only has to be a resolvable identity with a wallet. {@code cfm.issuer} still
     * travels with it — the certo activity in the orchestration reads the BPN from there.
     */
    public void deployParticipant(String name, String did, String bpn) {
        var tenant = given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer " + token("tenant-manager-api:write"))
                .contentType(ContentType.JSON)
                .body("""
                        { "properties": { "name": "%s" } }""".formatted(name))
                .post("/tenants");
        var tenantId = created(tenant, "tenant for " + did);

        var profile = given()
                .baseUri(baseUrl)
                .header("Authorization", "Bearer " + token("tenant-manager-api:write"))
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "identifier": "%s",
                          "vpaProperties": {
                            "cfm.issuer": {
                              "id": "%s",
                              "contractVersion": "1.0",
                              "memberOf": "Catena-X",
                              "bpn": "%s"
                            },
                            "cfm.connector": { "dataspaceProfiles": ["cx-neptune"] }
                          }
                        }""".formatted(did, did, bpn))
                .post("/tenants/{id}/participant-profiles", tenantId);
        var profileId = created(profile, "participant profile for " + did);
        log("   Tenant Manager:  participant profile %s deploying for %s (tenant %s)", profileId, did, tenantId);

        var contextId = new AtomicReference<String>();
        await().atMost(DEPLOY_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var current = given()
                    .baseUri(baseUrl)
                    .header("Authorization", "Bearer " + token("tenant-manager-api:read"))
                    .get("/tenants/{id}/participant-profiles/{pid}", tenantId, profileId)
                    .then().statusCode(200)
                    .extract().jsonPath();
            assertThat(current.getBoolean("error"))
                    .withFailMessage("participant profile %s reported a deployment error", profileId)
                    .isNotEqualTo(true);
            // the orchestration records what it provisioned under the profile's cfm.vpa.state
            var participantContextId = current.getString("properties.'cfm.vpa.state'.participantContextId");
            assertThat(participantContextId)
                    .withFailMessage("participant profile %s has no participant context yet", profileId)
                    .isNotBlank();
            contextId.set(participantContextId);
        });
        log("   Tenant Manager:  participant context %s exists for %s", contextId.get(), did);
    }

    /** The id of a created resource — any 2xx, since the service's exact code is not the contract. */
    private static String created(Response response, String what) {
        assertThat(response.statusCode())
                .withFailMessage("creating the %s failed with HTTP %d: %s", what, response.statusCode(), response.asString())
                .isBetween(200, 299);
        var id = response.jsonPath().getString("id");
        assertThat(id).withFailMessage("the created %s carries no id: %s", what, response.asString()).isNotBlank();
        return id;
    }

    private String token(String scope) {
        return tokenExchange.getParticipantToken("membership-hub", "issuer", scope);
    }
}
