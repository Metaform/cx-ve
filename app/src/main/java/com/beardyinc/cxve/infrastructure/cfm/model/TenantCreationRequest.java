package com.beardyinc.cxve.infrastructure.cfm.model;

import java.util.Map;

public record TenantCreationRequest(
        Map<String, Object> properties
) {
}
