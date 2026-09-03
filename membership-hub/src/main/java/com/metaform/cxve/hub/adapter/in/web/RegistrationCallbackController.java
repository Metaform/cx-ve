package com.metaform.cxve.hub.adapter.in.web;

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
 * the API's {@code OspRegistrationCallbackData}: the {@code externalId} the hub minted at
 * submission, the registration status (SUBMITTED/CONFIRMED/REJECTED) and an optional message.
 *
 * <p>The callback only RECORDS the registration's outcome on the membership — the Onboarding API
 * delivers it synchronously while the hub's own submission is still on the wire, and the
 * submitting thread picks the recorded outcome up and drives provisioning from there. Unknown
 * external ids are answered with 404: the callback is not for this hub instance's records.
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void onRegistrationStatus(@RequestBody RegistrationStatusUpdate update) {
        log.info("Registration status callback: externalId={}, status={}", update.externalId(), update.status());
        membershipService.onRegistrationStatus(update.externalId(), update.status(), update.message());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }

    /** Wire mirror of the Onboarding API's {@code OspRegistrationCallbackData}. */
    public record RegistrationStatusUpdate(String externalId, String status, String message) {
    }
}
