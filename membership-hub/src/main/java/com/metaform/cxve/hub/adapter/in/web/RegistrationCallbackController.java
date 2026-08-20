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
 * <p>CONFIRMED is the trigger for the provisioning leg — handled synchronously in this request so
 * the Onboarding API's (fire-and-forget) call carries the outcome in its log at least. Unknown
 * external ids are answered with 404: the callback is not for this hub instance's records.
 *
 * <p>Deliberately unauthenticated for now: the Onboarding API does not (yet) use the credentials
 * a provider registers with its callback, and this endpoint is only reachable cluster-internally.
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
        log.info("Registration status callback: externalId={}, state={}", update.externalId(), update.state());
        membershipService.onRegistrationStatus(update.externalId(), update.state(), update.message());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }

    /** Wire mirror of the Onboarding API's {@code OspRegistrationCallbackData}. */
    public record RegistrationStatusUpdate(String externalId, String state, String message) {
    }
}
