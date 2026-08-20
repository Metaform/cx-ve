package com.metaform.cxve.hub.adapter.out.cfm.model;

import java.util.Map;

public record TenantCreationRequest(
        Map<String, Object> properties
) {
}
