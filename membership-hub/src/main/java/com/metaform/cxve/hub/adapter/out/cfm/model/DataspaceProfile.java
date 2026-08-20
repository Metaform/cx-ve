package com.metaform.cxve.hub.adapter.out.cfm.model;

import java.util.List;
import java.util.Map;

public record DataspaceProfile(
        String id,
        Long version,
        DataspaceSpec dataspaceSpec,
        List<String> artifacts,
        List<DataspaceDeployment> deployments,
        Map<String, Object> properties
) {
}
