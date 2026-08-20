package com.metaform.cxve.hub.adapter.out.cfm.model;

import java.time.OffsetDateTime;
import java.util.Map;

public record Cell(
        String id,
        Long version,
        String state,
        OffsetDateTime stateTimestamp,
        String externalId,
        Map<String, Object> properties) {
}
