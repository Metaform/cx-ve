package com.metaform.cxve.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.RegistrationStatus;

/**
 * The read view of a registration served by the (beyond-spec) GET endpoints — everything a lost
 * status callback would have told the OSP, plus the process id the legacy endpoint returned at
 * submission: {@code applicationStatus} and {@code message} mirror the callback payload,
 * {@code bpnl} and {@code did} carry the identities as far as they have been assigned. NON_NULL,
 * like the callback.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegistrationProcessView(
        String externalId,
        String processId,
        RegistrationStatus applicationStatus,
        String message,
        String bpnl,
        String did
) {

    public static RegistrationProcessView from(OnboardingProcess process) {
        return new RegistrationProcessView(
                process.externalId(),
                process.id(),
                RegistrationStatus.from(process.state()),
                process.failureReason(),
                process.bpn(),
                process.holderId());
    }
}
