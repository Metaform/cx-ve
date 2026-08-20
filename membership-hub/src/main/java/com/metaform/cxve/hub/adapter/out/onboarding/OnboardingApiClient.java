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

    public OnboardingApiClient(@Qualifier("onboardingApiRestClient") RestClient restClient,
                               OspTokenProvider tokenProvider,
                               @Value("${onboarding-api.callback-url:http://localhost:8080/api/callbacks/registration-status}") String callbackUrl) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public void registerCallback() {
        // Only the callbackUrl is registered. The payload's authUrl/clientId/clientSecret are the
        // credentials the Onboarding API would use to authenticate its outbound callback call —
        // it does not do that today, and the hub's callback endpoint is cluster-internal.
        restClient.post()
                .uri("/api/administration/RegistrationStatus/callback")
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .body(Map.of("callbackUrl", callbackUrl))
                .retrieve()
                .toBodilessEntity();
        log.debug("Registered status callback '{}' with the Onboarding API", callbackUrl);
    }

    @Override
    public String submitRegistration(String externalId, String did, MemberData data) {
        return restClient.post()
                .uri("/api/v2/administration/registration/Network/partnerRegistration")
                .header("Authorization", "Bearer " + tokenProvider.getToken())
                .body(PartnerRegistrationPayload.from(externalId, did, data))
                .retrieve()
                .body(String.class);
    }
}
