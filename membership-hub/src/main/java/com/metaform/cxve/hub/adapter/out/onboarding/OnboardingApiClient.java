package com.metaform.cxve.hub.adapter.out.onboarding;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.port.OnboardingApi;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Calls the Onboarding API in the onboarding-service-provider role: registers the hub's status
 * callback (keyed server-side on the hub's client identity, so re-registering is an idempotent
 * overwrite) and submits partner registrations.
 */
@Service
public class OnboardingApiClient implements OnboardingApi {

    private static final Logger log = LoggerFactory.getLogger(OnboardingApiClient.class);

    private final RestClient restClient;
    private final OspTokenProvider tokenProvider;
    private final String callbackUrl;
    private final String callbackTokenUrl;
    private final String callbackClientId;
    private final String callbackClientSecret;

    public OnboardingApiClient(@Qualifier("onboardingApiRestClient") RestClient restClient,
                               OspTokenProvider tokenProvider,
                               @Value("${onboarding-api.callback.url:http://localhost:8080/api/callbacks/registration-status}") String callbackUrl,
                               @Value("${onboarding-api.callback.token-url:http://cxve.localhost/auth/osp/oauth2/token}") String callbackTokenUrl,
                               @Value("${onboarding-api.callback.client-id:membership-hub-callback}") String callbackClientId,
                               @Value("${onboarding-api.callback.client-secret:}") String callbackClientSecret) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.callbackUrl = callbackUrl;
        this.callbackTokenUrl = callbackTokenUrl;
        this.callbackClientId = callbackClientId;
        this.callbackClientSecret = callbackClientSecret;
    }

    @Override
    public void registerCallback() {
        // The registration carries the credentials for the OUTBOUND leg: the Onboarding API
        // fetches a client_credentials token from authUrl with this client id/secret and sends
        // it as the bearer on every status callback — which this app's callback endpoint
        // requires (CallbackSecurityConfig).
        restClient.post()
                .uri("/api/administration/registrationstatus/callback")
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .body(Map.of(
                        "callbackUrl", callbackUrl,
                        "authUrl", callbackTokenUrl,
                        "clientId", callbackClientId,
                        "clientSecret", callbackClientSecret))
                .retrieve()
                .toBodilessEntity();
        log.debug("Registered status callback '{}' (auth via client '{}' at {}) with the Onboarding API",
                callbackUrl, callbackClientId, callbackTokenUrl);
    }

    @Override
    public String submitRegistration(String externalId, String did, MemberData data) {
        return restClient.post()
                .uri("/api/administration/registration/network/partnerregistration")
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .body(PartnerRegistrationPayload.from(externalId, did, data))
                .retrieve()
                .body(String.class);
    }
}
