package com.beardyinc.cxve.infrastructure.cfm;

import com.beardyinc.cxve.infrastructure.cfm.model.Cell;
import com.beardyinc.cxve.infrastructure.cfm.model.DataspaceProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.Tenant;
import com.beardyinc.cxve.infrastructure.cfm.model.TenantCreationRequest;

import java.util.List;

public interface TenantManagerClient {
    List<Cell> listCells();
    Tenant createTenant(TenantCreationRequest newTenant);
    List<DataspaceProfile> listDataspaceProfiles();
    ParticipantProfile deployParticipantProfile(String tenantId, ParticipantProfile profile);
}
