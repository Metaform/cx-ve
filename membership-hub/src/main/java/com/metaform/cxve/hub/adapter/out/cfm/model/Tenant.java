package com.metaform.cxve.hub.adapter.out.cfm.model;

import java.util.Map;

public record Tenant(
        String id,
        Long version,
        Map<String, Object> properties
) {
}

