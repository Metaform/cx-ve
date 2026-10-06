package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/administration/registration/network")
public class NetworkController {

    private final NetworkService networkService;

    public NetworkController(NetworkService networkService) {
        this.networkService = networkService;
    }

    /**
     * Registers a partner company on behalf of the calling client (CX-0009 §2.2.1) — the token
     * identity (see {@link TokenClientId}) is recorded on the onboarding process and its status
     * callbacks are routed to that client's registered callback. Payloads missing a required
     * field (see {@link PartnerRegistrationData}) are rejected with 400; such rejections are
     * logged and the exact error message is returned (see {@link InvalidRequestShapeHandler}).
     *
     * @return the ID of the onboarding process. (BEYOND-SPEC deviation: the spec declares an
     *         empty 200 — the id in the body is what OSP clients correlate the process on, and
     *         the spec's tolerant reader permits it.)
     */
    @PostMapping("/partnerregistration")
    public String registerPartner(@Valid @RequestBody PartnerRegistrationData registrationData,
                                  @AuthenticationPrincipal Jwt token) {
        return networkService.registerPartner(TokenClientId.from(token), registrationData);
    }
}
