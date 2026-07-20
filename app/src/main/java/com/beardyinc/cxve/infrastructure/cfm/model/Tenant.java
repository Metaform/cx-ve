package com.beardyinc.cxve.infrastructure.cfm.model;

import java.util.Map;

public record Tenant(
        String id,
        Long version,
        Map<String, Object> properties
) {
}

