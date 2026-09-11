package com.metaform.cxve.hub.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.metaform.cxve.hub.application.MembershipService;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status-callback endpoint this app registers with the Onboarding API. The payload mirrors
 * the spec's {@code OspRegistrationCallbackData}: the {@code externalId} the hub minted at
 * submission, the {@code applicationStatus} (SUBMITTED/CONFIRMED/DECLINED), an optional message
 * and the CX-0010 BPN values ({@code bpnl} logged for reference — the hub requires the BPN up
 * front, so its own record stays authoritative). Answers 200 on receipt, per spec.
 *
 * <p>The callback is the DRIVER of the registration outcome: CONFIRMED advances the membership
 * and triggers the EDC provisioning on a background worker — no timing assumption is made about
 * whether it arrives while the hub's own submission is still on the wire (how the current
 * Onboarding API behaves), later, or redelivered. Unknown external ids are answered with 404:
 * the callback is not for this hub instance's records.
 *
 * <p>Authenticated: the caller presents a bearer obtained via client_credentials from the VE's
 * OSP IdP with the client this app registered alongside its callback URL — enforced by
 * {@link com.metaform.cxve.hub.config.CallbackSecurityConfig}.
 */
@RestController
@RequestMapping("/api/callbacks")
public class RegistrationCallbackController {

    private static final Logger log = LoggerFactory.getLogger(RegistrationCallbackController.class);

    private final MembershipService membershipService;

    public RegistrationCallbackController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/registration-status")
    public void onRegistrationStatus(@RequestBody RegistrationStatusUpdate update) {
        log.info("Registration status callback: externalId={}, applicationStatus={}, bpnl={}",
                update.externalId(), update.applicationStatus(), update.bpnl());
        membershipService.onRegistrationStatus(update.externalId(), update.applicationStatus(), update.message());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }

    /** Wire mirror of the spec's {@code OspRegistrationCallbackData}; tolerant reader. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegistrationStatusUpdate(String externalId, String applicationStatus, String message,
                                           String bpnl, String bpna, String bpns) {
    }
}
