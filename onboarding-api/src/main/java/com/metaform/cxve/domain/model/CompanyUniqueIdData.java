package com.metaform.cxve.domain.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** A company unique identifier (CX-0009 §3 CompanyUniqueIdData); both fields are Mandatory. */
public record CompanyUniqueIdData(
        @NotNull UniqueIdentifierId type,
        @NotBlank String value
) {
}
