package com.metaform.cxve.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests against a running VE (stood up by scripts/install-ve.sh) through its
 * gateway — black box, no dependency on the app module's classes; wire payloads are mirrored
 * locally (see {@link NewParticipantData}). The suite is not part of {@code check}/{@code build};
 * run it with {@code ./gradlew :app:e2eTest}.
 */
class VerificationEnvironmentE2ETest {
    private static final String ONBOARDING_API_URL = "http://cxve.localhost/onboarding";
    // Hostname under which the in-cluster Onboarding API can reach THIS test process: the
    // callback receiver (WireMock) runs on the host, and Docker Desktop/kind resolve
    // host.docker.internal to it from inside pods. Plain localhost would loop back to the pod.
    private static final String CALLBACK_HOST = "host.docker.internal";

    private static final long STATUS_CALLBACK_TIMEOUT_MINUTES = 1;

    // Fresh latch per test (see resetLatch); the listener below always counts down the current
    // one. Held in an AtomicReference because the listener is registered once, statically.
    private static final AtomicReference<byte[]> lastRequestPayload = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // The external registration-status consumer: WireMock listens on a dynamic port and serves
    // POST /registration/status, receiving the Onboarding API's status callbacks. The
    // ServeEventListener counts the latch down on every served /registration/status request.
    // h2c is disabled because the app's JDK HttpClient upgrades plain-http connections to
    // HTTP/2, and that path fails against Jetty with "Received RST_STREAM: Stream cancelled".
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true).extensions(new ServeEventListener() {
                @Override
                public String getName() {
                    return "registration-status-latch";
                }

                @Override
                public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
                    if (serveEvent.getRequest().getUrl().startsWith("/registration/status")) {
                        lastRequestPayload.set(serveEvent.getRequest().getBody());
                    }
                }
            }))
            .build();

    private final String TOKEN_EXCHANGE_URL = "http://cxve.localhost/api/auth/token";

    @BeforeEach
    void stubRegistrationStatus() {
        wiremock.stubFor(post(urlPathEqualTo("/registration/status"))
                .willReturn(okJson("{}")));

        // set callback url: status updates land on the WireMock stub above
        var callbackUrl = "http://%s:%d/registration/status".formatted(CALLBACK_HOST, wiremock.getPort());
        given()
                .baseUri(ONBOARDING_API_URL)
                .contentType(ContentType.JSON)
                .body(new SetCallbackRequest(callbackUrl, null, null, null))
                .post("/api/administration/RegistrationStatus/callback")
                .then().statusCode(204);
    }

    @Test
    void onboardParticipant() throws InterruptedException, IOException {
        var runId = UUID.randomUUID().toString();
        var name = "Test Participant " + runId;
        var shortName = "test-participant-" + runId;
        // kick off the onboarding
        onboardParticipant(name, shortName, runId);

        await().atMost(Duration.ofMinutes(1))
                .untilAsserted(() -> assertThat(lastRequestPayload.get()).isNotNull());

        var res = objectMapper.readValue(lastRequestPayload.get(), OnboardingResult.class);
        assertThat(res.participantContextId()).isNotNull();
        assertThat(res.failureReason()).isNull();
        assertThat(res.holderId()).contains("did:web:identity.cxve.localhost:" + shortName);
    }

    @Test
    void dataExchange() {
        // create CEL expressions
        //ctx.agent.claims.vc.withType('DataExchangeGovernanceCredential').hasClaim('contractVersion', '1.0.0')
        //ctx.agent.claims.vc.withType('MembershipCredential').hasClaim('memberOf', 'Catena-X')
        //ctx.agent.claims.vc.filter(c, c.type.exists(t, t == 'BpnCredential')).exists(c, c.credentialSubject.exists(cs, cs.bpn != null))

        // create asset
        // create policy
        // create contract definition
    }

    private static void onboardParticipant(String name, String shortName, String runId) {
        var newParticipant = NewParticipantData.builder()
                .name(name)
                .shortName(shortName)
                .externalId(runId)
                .city("Munich")
                .streetName("Otto-Hahn-Ring")
                .streetNumber("6")
                .zipCode("81739")
                .region("BY")
                .countryAlpha2Code("DE")
                .uniqueId("VAT_ID", "DE" + runId)
                .companyRole("ACTIVE_PARTICIPANT")
                .agreement("Catena-X", "ACTIVE")
                .autoSubmit(true)
                .build();

        // kick off the onboarding, then wait for the async callback
        given()
                .baseUri(VerificationEnvironmentE2ETest.ONBOARDING_API_URL)
                .contentType("application/json")
                .body(newParticipant)
                .post("/api/v2/administration/registration/Network/partnerRegistration")
                .then()
                .statusCode(200);
    }
}
