package com.metaform.cxve.hub.domain.model;

/**
 * A dataspace onboarding API's status callback, normalized: each dataspace reports in its own
 * wire format (Catena-X: {@code applicationStatus} CONFIRMED/DECLINED), its
 * {@link com.metaform.cxve.hub.domain.port.DataspaceOnboarding} maps that onto these three cases.
 *
 * @param externalId the hub-minted id the registration was submitted under
 * @param status     CONFIRMED — holder registered and credentials offered; DECLINED — terminally
 *                   refused; PENDING — anything in between, nothing for the hub to do
 * @param message    the onboarding API's explanation, if any (kept as the rejection reason)
 */
public record RegistrationOutcome(String externalId, Status status, String message) {

    public enum Status {
        CONFIRMED,
        DECLINED,
        PENDING
    }
}
