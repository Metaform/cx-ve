package com.beardyinc.cxve.infrastructure.cfm;

import com.beardyinc.cxve.auth.TokenProvider;
import com.beardyinc.cxve.infrastructure.cfm.model.Cell;
import com.beardyinc.cxve.infrastructure.cfm.model.DataspaceProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.Tenant;
import com.beardyinc.cxve.infrastructure.cfm.model.TenantCreationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class TenantManagerClientImpl implements TenantManagerClient {
    private final TokenProvider tokenProvider;
    private final RestClient restClient;

    public TenantManagerClientImpl(@Autowired TokenProvider tokenProvider,
                                   @Qualifier("tenantManagerClient") RestClient restClient) {
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
    }

    @Override
    public List<Cell> listCells() {
        var data = restClient.get()
                .uri("/cells")
                .header("Authorization", "Bearer " + getToken("cfm-read"))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<List<Cell>>() {
                });
        return data.getBody();
    }

    @Override
    public Tenant createTenant(TenantCreationRequest newTenant) {
        return restClient.post()
                .uri("/tenants")
                .header("Authorization", "Bearer " + getToken("cfm-write"))
                .body(newTenant)
                .retrieve()
                .toEntity(Tenant.class)
                .getBody();
    }

    @Override
    public List<DataspaceProfile> listDataspaceProfiles() {
        return restClient.get()
                .uri("/dataspace-profiles")
                .header("Authorization", "Bearer " + getToken("cfm-read"))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<List<DataspaceProfile>>() {
                }).getBody();
    }

    @Override
    public ParticipantProfile deployParticipantProfile(String tenantId, ParticipantProfile profile) {
        return restClient.post()
                .uri("/tenants/{id}/participant-profiles", tenantId)
                .header("Authorization", "Bearer " + getToken("cfm-write"))
                .body(profile)
                .retrieve()
                .toEntity(ParticipantProfile.class)
                .getBody();
    }

    @Override
    public ParticipantProfile getParticipantProfile(String tenantId, String participantProfileId) {
        var response = restClient.get()
                .uri("/tenants/{id}/participant-profiles/{participantID}", tenantId, participantProfileId)
                .header("Authorization", "Bearer " + getToken("cfm-read"))
                .retrieve();
        return response
                .toEntity(ParticipantProfile.class)
                .getBody();
    }

    private String getToken(String scope) {
        return tokenProvider.getToken(null, scope);
    }
}
