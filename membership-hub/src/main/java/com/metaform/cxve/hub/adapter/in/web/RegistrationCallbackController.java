package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.application.MembershipService;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status-callback endpoints this app registers with the dataspaces' onboarding APIs — one
 * per dataspace, {@code /api/callbacks/{dataspace}/registration-status}. The body is in the
 * dataspace's own wire format (Catena-X: the spec's {@code OspRegistrationCallbackData}) and is
 * read by that dataspace's onboarding; it always carries the {@code externalId} the hub minted at
 * submission. Answers 200 on receipt.
 *
 * <p>The callback is the DRIVER of the registration outcome, and a confirmation is the
 * membership's terminal success: the onboarding API confirms only once it has registered the
 * credential holder AND had the issuer offer it its credentials. No timing assumption is made
 * about whether the callback arrives while the hub's own submission is still on the wire, later,
 * or redelivered. Unknown external ids — and those of another dataspace's memberships — are
 * answered with 404: the callback is not for this hub's records. An unserved dataspace is a 404
 * too: there is no such endpoint.
 *
 * <p>Authenticated: the caller presents a bearer obtained via client_credentials from the VE's
 * OSP IdP with the client this app registered alongside its callback URL — enforced by
 * {@link com.metaform.cxve.hub.config.CallbackSecurityConfig}.
 */
@RestController
@RequestMapping("/api/callbacks")
public class RegistrationCallbackController {

    private final MembershipService membershipService;

    public RegistrationCallbackController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/{dataspace}/registration-status")
    public void onRegistrationStatus(@PathVariable String dataspace, @RequestBody Map<String, Object> callback) {
        membershipService.onRegistrationStatus(dataspace, callback);
    }

    @ExceptionHandler({NoSuchElementException.class, DataspaceOnboarding.UnknownDataspaceException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(RuntimeException e) {
        return e.getMessage();
    }
}
