package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import com.metaform.cxve.hub.adapter.out.onboarding.ClientCredentials;
import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Decade-X onboarding through its membership-application API (the VE's dx-onboarding-api stub):
 * the hub sets its decision webhook and submits applications under its own reference — the
 * {@code externalId} — which the decision echoes back as {@code applicationRef}.
 *
 * <p>An APPROVED decision maps to the hub's CONFIRMED. Unlike Catena-X's confirmation, the stub's
 * approval does not mean credentials were offered: no Decade-X issuer is set up in the VE yet.
 */
public class DecadeXOnboarding implements DataspaceOnboarding {

    public static final String DATASPACE = "decade-x";

    private static final Logger log = LoggerFactory.getLogger(DecadeXOnboarding.class);

    private final RestClient restClient;
    private final ClientCredentials credentials;
    private final DataspaceProperties.Callback callback;
    private final RegistrationValidator validator;

    public DecadeXOnboarding(DataspaceProperties.Onboarding onboarding, RegistrationValidator validator) {
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
     * Decade-X membership is the dataspace itself; the member number is carried under {@code bpn}
     * too, because that is the key the certo provisioning activity reads a member's id from.
     */
    @Override
    public Map<String, Object> issuerProperties(String did, MemberData data) {
        return Map.of(
                "id", did,
                "memberOf", "Decade-X",
                "memberNumber", data.memberId(),
                "bpn", data.memberId());
    }

    @Override
    public void registerCallback() {
        var webhook = new HashMap<String, Object>();
        webhook.put("url", callback.url());
        webhook.put("tokenUrl", callback.tokenUrl());
        webhook.put("clientId", callback.clientId());
        webhook.put("clientSecret", callback.clientSecret() == null ? "" : callback.clientSecret());
        restClient.put()
                .uri("/api/v1/webhook")
                .header("Authorization", "Bearer " + credentials.getToken())
                .body(webhook)
                .retrieve()
                .toBodilessEntity();
        log.debug("Registered decision webhook '{}' with the Decade-X onboarding API", callback.url());
    }

    @Override
    public String submitRegistration(String externalId, String did, MemberData data) {
        var registration = registration(data);
        var receipt = restClient.post()
                .uri("/api/v1/applications")
                .header("Authorization", "Bearer " + credentials.getToken())
                .body(Map.of(
                        "applicationRef", externalId,
                        "legalName", data.name(),
                        "memberNumber", data.memberId(),
                        "did", did,
                        "country", registration.country(),
                        "contactEmail", registration.contactEmail()))
                .retrieve()
                .body(Receipt.class);
        if (receipt == null || receipt.applicationId() == null) {
            throw new IllegalStateException("The Decade-X onboarding API returned no application id");
        }
        return receipt.applicationId();
    }

    /** Reads a decision: {@code applicationRef}, {@code decision} APPROVED/REJECTED, {@code reason}. */
    @Override
    public RegistrationOutcome readCallback(Map<String, Object> body) {
        var applicationRef = string(body, "applicationRef");
        var decision = string(body, "decision");
        log.info("Decade-X decision: applicationRef={}, applicationId={}, decision={}",
                applicationRef, string(body, "applicationId"), decision);
        var status = switch (decision == null ? "" : decision.toUpperCase()) {
            case "APPROVED" -> RegistrationOutcome.Status.CONFIRMED;
            case "REJECTED" -> RegistrationOutcome.Status.DECLINED;
            default -> RegistrationOutcome.Status.PENDING;
        };
        return new RegistrationOutcome(applicationRef, status, string(body, "reason"));
    }

    private DecadeXRegistration registration(MemberData data) {
        return validator.read(data.registration(), DecadeXRegistration.class, DATASPACE);
    }

    private static String string(Map<String, Object> body, String key) {
        var value = body.get(key);
        return value == null ? null : value.toString();
    }

    private record Receipt(String applicationId) {
    }
}
