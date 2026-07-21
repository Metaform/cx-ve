package com.metaform.cxve.infrastructure.cfm;

import com.metaform.cxve.infrastructure.cfm.model.Cell;
import com.metaform.cxve.infrastructure.cfm.model.DataspaceProfile;
import com.metaform.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.metaform.cxve.infrastructure.cfm.model.Tenant;
import com.metaform.cxve.infrastructure.cfm.model.TenantCreationRequest;
import java.util.List;

public interface TenantManagerClient {
    List<Cell> listCells();
    Tenant createTenant(TenantCreationRequest newTenant);
    List<DataspaceProfile> listDataspaceProfiles();
    ParticipantProfile deployParticipantProfile(String tenantId, ParticipantProfile profile);
    ParticipantProfile getParticipantProfile(String tenantId, String profileId);
}
