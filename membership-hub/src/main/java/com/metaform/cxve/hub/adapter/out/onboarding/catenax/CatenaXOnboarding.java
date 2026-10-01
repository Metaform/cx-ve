package com.metaform.cxve.hub.adapter.out.onboarding.catenax;

import com.metaform.cxve.hub.adapter.out.onboarding.ClientCredentials;
import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Catena-X onboarding through the CX onboarding API (CX-0009 registration, CX-0010 callback), in
 * the onboarding-service-provider role: registers the hub's status callback (keyed server-side on
 * the hub's client identity, so re-registering is an idempotent overwrite) and submits partner
 * registrations. The API registers the member as a credential holder with the IssuerService and
 * has it offer the member the Catena-X credentials; its CONFIRMED callback means exactly that.
 */
public class CatenaXOnboarding implements DataspaceOnboarding {

    public static final String DATASPACE = "catena-x";

    private static final Logger log = LoggerFactory.getLogger(CatenaXOnboarding.class);

    private final RestClient restClient;
    private final ClientCredentials credentials;
    private final DataspaceProperties.Callback callback;
    private final RegistrationValidator validator;

    public CatenaXOnboarding(DataspaceProperties.Onboarding onboarding, RegistrationValidator validator) {
        this.restClient = RestClient.builder().baseUrl(onboarding.url()).build();
        this.credentials = new ClientCredentials(onboarding.auth());
        this.callback = onboarding.callback();
        this.validator = validator;
    }

    @Override
    public String dataspace() {
        return DATASPACE;
    }

    @Override
    public void validate(MemberData data) {
        registration(data);
    }

    /**
     * {@code memberOf} is the member's ACTIVE agreements — what the dataspace's Membership policy
     * checks ({@code memberOf == 'Catena-X'}); {@code bpn} is what the certo activity reads.
     */
    @Override
    public Map<String, Object> issuerProperties(String did, MemberData data) {
        var memberOf = registration(data).agreements().stream()
                .filter(CatenaXRegistration.AgreementConsent::hasActiveConsent)
                .map(CatenaXRegistration.AgreementConsent::agreementId)
                .toList();
        return Map.of(
                "id", did,
                "contractVersion", "1.0",
                "memberOf", String.join(", ", memberOf),
                "bpn", data.memberId());
    }

    @Override
    public void registerCallback() {
        // The registration carries the credentials for the OUTBOUND leg: the onboarding API
        // fetches a client_credentials token from authUrl with this client id/secret and sends
        // it as the bearer on every status callback — which this app's callback endpoint
        // requires (CallbackSecurityConfig).
        restClient.post()
                .uri("/api/administration/registrationstatus/callback")
                .header("Authorization", "Bearer " + credentials.getToken())
                .body(Map.of(
                        "callbackUrl", callback.url(),
                        "authUrl", callback.tokenUrl(),
                        "clientId", callback.clientId(),
                        "clientSecret", callback.clientSecret() == null ? "" : callback.clientSecret()))
                .retrieve()
                .toBodilessEntity();
        log.debug("Registered status callback '{}' (auth via client '{}' at {}) with the CX onboarding API",
                callback.url(), callback.clientId(), callback.tokenUrl());
    }

    @Override
    public String submitRegistration(String externalId, String did, MemberData data) {
        return restClient.post()
                .uri("/api/administration/registration/network/partnerregistration")
                .header("Authorization", "Bearer " + credentials.getToken())
                .body(PartnerRegistrationPayload.from(externalId, did, data, registration(data)))
                .retrieve()
                .body(String.class);
    }

    /**
     * Reads the spec's {@code OspRegistrationCallbackData}: {@code applicationStatus}
     * SUBMITTED/CONFIRMED/DECLINED, an optional message and the CX-0010 BPN values ({@code bpnl}
     * logged for reference — the hub requires the BPN up front, so its own record stays
     * authoritative).
     */
    @Override
    public RegistrationOutcome readCallback(Map<String, Object> body) {
        var externalId = string(body, "externalId");
        var status = string(body, "applicationStatus");
        log.info("CX registration status callback: externalId={}, applicationStatus={}, bpnl={}",
                externalId, status, string(body, "bpnl"));
        var outcome = switch (status == null ? "" : status.toUpperCase()) {
            case "CONFIRMED" -> RegistrationOutcome.Status.CONFIRMED;
            case "DECLINED" -> RegistrationOutcome.Status.DECLINED;
            default -> RegistrationOutcome.Status.PENDING;
        };
        return new RegistrationOutcome(externalId, outcome, string(body, "message"));
    }

    private CatenaXRegistration registration(MemberData data) {
        return validator.read(data.registration(), CatenaXRegistration.class, DATASPACE);
    }

    private static String string(Map<String, Object> body, String key) {
        var value = body.get(key);
        return value == null ? null : value.toString();
    }
}
