package com.metaform.cxve.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload of the status update POSTed to an onboarding service provider's registered callback
 * (the spec's {@code OspRegistrationCallbackData}): the caller-supplied {@code externalId}, the
 * {@code applicationStatus}, an optional {@code message} (e.g. the decline reason) and the BPN
 * values per CX-0010. Serialized NON_NULL: {@code bpna}/{@code bpns} are never populated by this
 * implementation, and {@code bpnl} — spec-Mandatory on every callback — is simply absent on a
 * DECLINED emitted before a BPN was assigned, since no value can exist yet (a known spec defect;
 * see docs/testing/registration-api-test-plan.md §10.5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OspRegistrationCallbackData(
        String externalId,
        RegistrationStatus applicationStatus,
        String message,
        String bpnl,
        String bpna,
        String bpns
) {
}
