package com.beardyinc.cxve.interfaces.dto;

import java.util.List;

public record ErrorDetails(
        String errorCode,
        String type,
        String message,
        List<ErrorParameter> parameters
) {
}
