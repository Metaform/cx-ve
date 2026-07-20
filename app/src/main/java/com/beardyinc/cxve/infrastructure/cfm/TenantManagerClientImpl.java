package com.beardyinc.cxve.infrastructure.cfm;

import com.beardyinc.cxve.auth.TokenProvider;
import com.beardyinc.cxve.infrastructure.cfm.model.Cell;
import com.beardyinc.cxve.infrastructure.cfm.model.DataspaceProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.Tenant;
import com.beardyinc.cxve.infrastructure.cfm.model.TenantCreationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TenantManagerClientImpl implements TenantManagerClient {
    private final TokenProvider tokenProvider;

    public TenantManagerClientImpl(@Autowired TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public List<Cell> listCells() {
        return List.of();
    }

    @Override
    public Tenant createTenant(TenantCreationRequest newTenant) {
        return null;
    }

    @Override
    public List<DataspaceProfile> listDataspaceProfiles() {
        return List.of();
    }

    @Override
    public ParticipantProfile deployParticipantProfile(String tenantId, ParticipantProfile profile) {
        return null;
    }
}
