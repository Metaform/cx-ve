package com.metaform.cxve.e2e;

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // Status callbacks to await before the test may finish. Raise when the Onboarding API
    // reports more state transitions per onboarding.
    private static final int EXPECTED_STATUS_CALLBACKS = 1;
    private static final long STATUS_CALLBACK_TIMEOUT_MINUTES = 1;

    // Fresh latch per test (see resetLatch); the listener below always counts down the current
    // one. Held in an AtomicReference because the listener is registered once, statically.
    private static final AtomicReference<CountDownLatch> statusCallbackLatch = new AtomicReference<>();

    // The external registration-status consumer: WireMock listens on a dynamic port and serves
    // POST /registration/status, receiving the Onboarding API's status callbacks. The
    // ServeEventListener counts the latch down on every served /registration/status request.
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().extensions(new ServeEventListener() {
                @Override
                public String getName() {
                    return "registration-status-latch";
                }

                @Override
                public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
                    if (serveEvent.getRequest().getUrl().startsWith("/registration/status")) {
                        statusCallbackLatch.get().countDown();
                    }
                }
            }))
            .build();

    private final String TOKEN_EXCHANGE_URL = "http://cxve.localhost/api/auth/token";

    @BeforeEach
    void stubRegistrationStatus() {
        statusCallbackLatch.set(new CountDownLatch(EXPECTED_STATUS_CALLBACKS));
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
    void onboardParticipant() throws InterruptedException {
        var runId = UUID.randomUUID().toString();
        var name = "Test Participant " + runId;
        var shortName = "test-participant-" + runId;
        // kick off the onboarding
        onboardParticipant(name, shortName, runId);

        // onboarding proceeds asynchronously — block until the VE has delivered the expected
        // status callback(s) to the WireMock receiver, or fail after the timeout
        assertTrue(statusCallbackLatch.get().await(STATUS_CALLBACK_TIMEOUT_MINUTES, TimeUnit.MINUTES),
                "expected %d status callback(s) on /registration/status within %d minutes"
                        .formatted(EXPECTED_STATUS_CALLBACKS, STATUS_CALLBACK_TIMEOUT_MINUTES));
    }

    @Test
    void dataExchange(){

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
                .baseUri(ONBOARDING_API_URL)
                .contentType("application/json")
                .body(newParticipant)
                .post("/api/v2/administration/registration/Network/partnerRegistration")
                .then()
                .statusCode(200);
    }
}
