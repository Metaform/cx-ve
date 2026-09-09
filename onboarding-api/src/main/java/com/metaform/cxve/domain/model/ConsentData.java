package com.metaform.cxve.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A consent the OSP has collected from the prospective participant (CX-0009 §3 Consent).
 * {@code fileIds} would reference uploaded consent documents; this deployment does not offer the
 * (per spec §2.2.3 optional) file upload endpoint, so the ids are carried but nothing dereferences
 * them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsentData(
        @NotNull ConsentKind kind,
        List<String> fileIds
) {

    /** The spec: "MUST be provided if kind is OTHER" — an OTHER consent without its documents is unverifiable. */
    @JsonIgnore
    @AssertTrue(message = "fileIds must be provided when kind is OTHER")
    public boolean isFileIdsPresentForOtherKind() {
        return kind != ConsentKind.OTHER || (fileIds != null && !fileIds.isEmpty());
    }
}
