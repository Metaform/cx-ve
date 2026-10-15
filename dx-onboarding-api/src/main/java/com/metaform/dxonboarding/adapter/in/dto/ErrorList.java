package com.metaform.dxonboarding.adapter.in.dto;

import java.util.List;

/** An error answer, shaped as the data plane relays one: {@code {"errors": [...]}}. */
public record ErrorList(List<String> errors) {

    public static ErrorList of(String... errors) {
        return new ErrorList(List.of(errors));
    }
}
