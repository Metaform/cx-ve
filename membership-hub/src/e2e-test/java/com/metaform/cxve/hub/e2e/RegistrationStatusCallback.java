package com.metaform.cxve.hub.e2e;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * E2e-local mirror of the status-callback payload the Onboarding API POSTs to the registered
 * callback URL (the spec's {@code OspRegistrationCallbackData}): the externalId supplied at
 * registration, the applicationStatus (SUBMITTED, CONFIRMED or DECLINED), an optional message
 * (e.g. the decline reason) and the CX-0010 {@code bpnl}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationStatusCallback(String externalId, String applicationStatus, String message, String bpnl) {
}
