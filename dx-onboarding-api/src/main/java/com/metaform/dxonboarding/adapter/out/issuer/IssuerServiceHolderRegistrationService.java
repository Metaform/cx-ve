package com.metaform.dxonboarding.adapter.out.issuer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.metaform.dxonboarding.adapter.out.auth.TokenProvider;
import com.metaform.dxonboarding.config.IssuerServiceProperties;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.port.HolderRegistrationService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Registers the holder through the IssuerService's admin API, under the participant's DID as
 * holder id. Its {@code properties} are what the issuer's holder attestation turns into claims: the
 * DID ({@code id}) and the Decade-X-ID ({@code decadeXId}) — what the Decade-X membership
 * credential definition maps.
 *
 * <p>Holder ids are unique across the issuer, and a participant hosted elsewhere may join Catena-X
 * too: its DID can already be a holder, registered by the Catena-X onboarding. A 409 therefore
 * MERGES the Decade-X claims into the existing holder — keeping its name and every other dataspace's
 * properties — instead of being taken as success, which would leave the holder without a
 * {@code decadeXId} and fail the credential's generation later, out of sight.
 *
 * <p>Authorized like the Catena-X onboarding API: a workload token exchanged under the
 * {@code issuer-service.token-resource} mapping (the platform's {@code sudo}) for
 * {@code issuer-admin-api:admin} — the scope the admin API accepts from a caller that is not one of
 * its participants.
 */
@Component
public class IssuerServiceHolderRegistrationService implements HolderRegistrationService {

    static final String SCOPE = "issuer-admin-api:admin";

    private static final Logger log = LoggerFactory.getLogger(IssuerServiceHolderRegistrationService.class);

    private final RestClient restClient;
    private final TokenProvider tokenProvider;
    private final IssuerServiceProperties properties;

    public IssuerServiceHolderRegistrationService(@Qualifier("issuerServiceClient") RestClient restClient,
                                                  TokenProvider tokenProvider, IssuerServiceProperties properties) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    @Override
    public void registerHolder(OnboardingRequest request) {
        var did = request.connectorId();
        var claims = Map.<String, Object>of(
                "id", did,
                "decadeXId", Objects.requireNonNull(request.legalEntityId(), "the request has no Decade-X-ID yet"));
        var token = tokenProvider.getToken(properties.tokenResource(), SCOPE);
        try {
            restClient.post()
                    .uri("/v1/participants/{issuerContextId}/holders", properties.issuerContextId())
                    .header("Authorization", "Bearer " + token)
                    .body(holder(did, request.data().legalEntity().legalName(), claims))
                    .retrieve()
                    .toBodilessEntity();
            log.info("registered holder {} with Decade-X-ID {}", did, request.legalEntityId());
        } catch (HttpClientErrorException.Conflict e) {
            mergeInto(did, claims, token);
        }
    }

    private void mergeInto(String did, Map<String, Object> claims, String token) {
        var existing = restClient.get()
                .uri("/v1/participants/{issuerContextId}/holders/{holderId}", properties.issuerContextId(), did)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(ExistingHolder.class);
        if (existing == null) {
            throw new IllegalStateException("The IssuerService reported holder %s as existing, but returned none".formatted(did));
        }
        var merged = new HashMap<String, Object>(existing.properties() == null ? Map.of() : existing.properties());
        merged.putAll(claims);
        restClient.put()
                .uri("/v1/participants/{issuerContextId}/holders", properties.issuerContextId())
                .header("Authorization", "Bearer " + token)
                .body(holder(did, existing.holderName(), merged))
                .retrieve()
                .toBodilessEntity();
        log.info("holder {} already registered — added the Decade-X claims to it ({})", did, claims.get("decadeXId"));
    }

    private static Map<String, Object> holder(String did, String name, Map<String, Object> claims) {
        var holder = new HashMap<String, Object>();
        holder.put("holderId", did);
        holder.put("did", did);
        holder.put("name", name);
        holder.put("properties", claims);
        return holder;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExistingHolder(String holderId, String did, String holderName, Map<String, Object> properties) {
    }
}
