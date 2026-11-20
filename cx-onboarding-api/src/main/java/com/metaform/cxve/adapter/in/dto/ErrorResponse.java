package com.metaform.cxve.adapter.in.dto;

import java.util.List;
import java.util.Map;

public record ErrorResponse(
        String type,
        String title,
        Integer status,
        Map<String, List<String>> errors,
        String errorId,
        List<ErrorDetails> details
) {
}
