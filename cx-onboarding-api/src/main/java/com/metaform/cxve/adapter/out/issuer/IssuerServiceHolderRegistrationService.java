package com.metaform.cxve.adapter.out.issuer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.metaform.cxve.adapter.out.auth.TokenProvider;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.HolderRegistrationService;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Registers holders through the IssuerService Admin API, replacing what the CFM registration agent
 * used to do inside the provisioning orchestration: {@code POST
 * /v1/participants/{issuerContextId}/holders} with the participant's DID as both {@code did}
 * and {@code holderId}.
 *
 * <p>The holder {@code properties} are the attestation data of the issuer's holder attestation:
 * the seeded credential definitions map {@code bpn}, {@code memberOf}, {@code contractVersion}
 * and {@code id} from them into the credential subjects, and every mapping is required — a holder
 * missing them makes credential generation fail ("Failed to apply mapping definition") and leaves
 * issuance stuck. Shape and content mirror the {@code cfm.issuer} VPA properties the registration
 * agent used to pass.
 *
 * <p>Holder ids are unique across the issuer, which serves every dataspace: the DID may already
 * be a holder — left by an earlier attempt of this participant (a dead registration frees its DID
 * for a new one, possibly with a corrected BPN), or registered by another dataspace's onboarding
 * (an external participant may join DECADE-X too, whose onboarding registers holders with its own
 * properties). A 409 therefore MERGES: the Catena-X properties are put on the existing holder,
 * replacing stale values of their own keys and keeping everything else — its name and the other
 * dataspaces' properties. Taking the 409 as success instead would leave the holder without these
 * properties, or with an earlier attempt's BPN, and the credentials would fail to generate or carry
 * the wrong identity, out of sight of this process.
 *
 * <p>Auth mirrors that agent, too: the workload token is exchanged (resource {@code sudo}, scope
 * {@code issuer-admin-api:admin}). The admin scope is required — the exchanged token's {@code sub}
 * is no participant context the IssuerService knows, and non-admin scopes fail its caller
 * resolution with "No participant for 'sub = ...' found".
 */
@Service
public class IssuerServiceHolderRegistrationService implements HolderRegistrationService {

    private static final String SCOPE = "issuer-admin-api:admin";

    private static final Logger log = LoggerFactory.getLogger(IssuerServiceHolderRegistrationService.class);

    private final TokenProvider tokenProvider;
    private final RestClient restClient;
    private final String issuerContextId;
    private final String tokenResource;
    private final String memberOf;

    public IssuerServiceHolderRegistrationService(TokenProvider tokenProvider,
                                                  @Qualifier("issuerServiceClient") RestClient restClient,
                                                  @Value("${issuer-service.issuer-context-id:issuer}") String issuerContextId,
                                                  @Value("${issuer-service.token-resource:sudo}") String tokenResource,
                                                  @Value("${issuer-service.member-of:Catena-X}") String memberOf) {
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
        this.issuerContextId = issuerContextId;
        this.tokenResource = tokenResource;
        this.memberOf = memberOf;
    }

    @Override
    public void registerHolder(OnboardingProcess process, PartnerRegistrationData registrationData) {
        var did = process.holderId();
        var properties = holderProperties(process, registrationData);
        var token = tokenProvider.getToken(tokenResource, SCOPE);
        try {
            restClient.post()
                    .uri("/v1/participants/{issuerContextId}/holders", issuerContextId)
                    .header("Authorization", "Bearer " + token)
                    .body(holder(did, registrationData.name(), properties))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Registered holder '{}' with the IssuerService for onboarding {}", did, process.id());
        } catch (HttpClientErrorException.Conflict e) {
            mergeInto(did, properties, token);
            log.info("Holder '{}' already registered with the IssuerService — put this registration's properties "
                    + "on it, keeping the others, for onboarding {}", did, process.id());
        }
    }

    /** Puts the properties on the existing holder: its own keys replaced, everything else kept. */
    private void mergeInto(String did, Map<String, Object> properties, String token) {
        var existing = restClient.get()
                .uri("/v1/participants/{issuerContextId}/holders/{holderId}", issuerContextId, did)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(ExistingHolder.class);
        if (existing == null) {
            throw new IllegalStateException("The IssuerService reported holder %s as existing, but returned none".formatted(did));
        }
        var merged = new HashMap<String, Object>(existing.properties() == null ? Map.of() : existing.properties());
        merged.putAll(properties);
        restClient.put()
                .uri("/v1/participants/{issuerContextId}/holders", issuerContextId)
                .header("Authorization", "Bearer " + token)
                .body(holder(did, existing.holderName(), merged))
                .retrieve()
                .toBodilessEntity();
    }

    private static Map<String, Object> holder(String did, String name, Map<String, Object> properties) {
        var holder = new HashMap<String, Object>();
        holder.put("did", did);
        holder.put("holderId", did);
        holder.put("name", name);
        holder.put("properties", properties);
        return holder;
    }

    private Map<String, Object> holderProperties(OnboardingProcess process, PartnerRegistrationData registrationData) {
        // memberOf is deliberately a configured constant, not derived from the registration: the
        // MembershipCredential's memberOf claim must satisfy the Catena-X CEL Membership policy
        // (memberOf == 'Catena-X'), and neither flow carries a value to derive it from anymore
        // (§2.2.1 lost its agreements; §2.2.2 consents name policy documents, not memberships).
        // The BPN is assigned before the holder registration runs (the BPN step precedes it);
        // contractVersion is the fixed value the registration agent used to send.
        return Map.of(
                "id", process.holderId(),
                "contractVersion", "1.0",
                "memberOf", memberOf,
                "bpn", process.bpn());
    }

    /** A holder as the admin API returns it; only what a merge carries over is read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExistingHolder(String holderId, String did, String holderName, Map<String, Object> properties) {
    }
}
