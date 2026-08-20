package com.metaform.cxve.hub.adapter.out.cfm;

import com.metaform.cxve.hub.adapter.out.auth.TokenProvider;
import com.metaform.cxve.hub.adapter.out.cfm.model.Cell;
import com.metaform.cxve.hub.adapter.out.cfm.model.DataspaceProfile;
import com.metaform.cxve.hub.adapter.out.cfm.model.ParticipantProfile;
import com.metaform.cxve.hub.adapter.out.cfm.model.Tenant;
import com.metaform.cxve.hub.adapter.out.cfm.model.TenantCreationRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the CFM Tenant Manager, moved here from the Onboarding API when the EDC
 * provisioning became the hub's responsibility. Tokens come from the platform's jwtlet (RFC 8693
 * workload-token exchange) under the configured resource, whose mapping must carry the
 * {@code tenant-manager-api:read/write} scopes.
 */
@Service
public class TenantManagerClientImpl implements TenantManagerClient {
    private final TokenProvider tokenProvider;
    private final RestClient restClient;
    private final String tokenResource;

    public TenantManagerClientImpl(TokenProvider tokenProvider,
                                   @Qualifier("tenantManagerClient") RestClient restClient,
                                   @Value("${tenant-manager.token-resource:issuer}") String tokenResource) {
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
        this.tokenResource = tokenResource;
    }

    @Override
    public List<Cell> listCells() {
        return restClient.get()
                .uri("/cells")
                .header("Authorization", "Bearer " + getToken("tenant-manager-api:read"))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<List<Cell>>() {
                })
                .getBody();
    }

    @Override
    public Tenant createTenant(TenantCreationRequest newTenant) {
        return restClient.post()
                .uri("/tenants")
                .header("Authorization", "Bearer " + getToken("tenant-manager-api:write"))
                .body(newTenant)
                .retrieve()
                .toEntity(Tenant.class)
                .getBody();
    }

    @Override
    public List<DataspaceProfile> listDataspaceProfiles() {
        return restClient.get()
                .uri("/dataspace-profiles")
                .header("Authorization", "Bearer " + getToken("tenant-manager-api:read"))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<List<DataspaceProfile>>() {
                }).getBody();
    }

    @Override
    public ParticipantProfile deployParticipantProfile(String tenantId, ParticipantProfile profile) {
        return restClient.post()
                .uri("/tenants/{id}/participant-profiles", tenantId)
                .header("Authorization", "Bearer " + getToken("tenant-manager-api:write"))
                .body(profile)
                .retrieve()
                .toEntity(ParticipantProfile.class)
                .getBody();
    }

    @Override
    public ParticipantProfile getParticipantProfile(String tenantId, String participantProfileId) {
        return restClient.get()
                .uri("/tenants/{id}/participant-profiles/{participantID}", tenantId, participantProfileId)
                .header("Authorization", "Bearer " + getToken("tenant-manager-api:read"))
                .retrieve()
                .toEntity(ParticipantProfile.class)
                .getBody();
    }

    private String getToken(String scope) {
        return tokenProvider.getToken(tokenResource, scope);
    }
}
