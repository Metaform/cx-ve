package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.CallbackResponseData;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Callback registration of onboarding service providers. Registrations are keyed on the caller's
 * token identity (see {@link TokenClientId}), so a provider can only read and replace its own —
 * the clientId inside the payload is NOT the key; it is the provider's OAuth2 client id for the
 * outbound call to its callback endpoint.
 */
@RestController
@RequestMapping("/api/administration/registrationstatus")
public class RegistrationStatusController {

    private final RegistrationStatusService registrationStatusService;

    public RegistrationStatusController(RegistrationStatusService registrationStatusService) {
        this.registrationStatusService = registrationStatusService;
    }

    /**
     * Gets the callback address the calling onboarding service provider has registered — without
     * the client secret, which is write-only per spec. Nothing registered yet answers 200 with an
     * empty body ("Empty if not configured").
     */
    @GetMapping("/callback")
    public CallbackResponseData getCallbackAddress(@AuthenticationPrincipal Jwt token) {
        return CallbackResponseData.from(registrationStatusService.getCallbackAddress(TokenClientId.from(token)));
    }

    /**
     * Sets or updates the callback address of the calling onboarding service provider. All four
     * fields are mandatory (spec §2.2.4); like every administration endpoint this requires the
     * {@code configure_partner_registration} scope (enforced in
     * {@link com.metaform.cxve.config.ApiSecurityConfig}).
     */
    @PostMapping("/callback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCallbackAddress(@Valid @RequestBody CallbackRequestData callbackData, @AuthenticationPrincipal Jwt token) {
        registrationStatusService.setCallbackAddress(TokenClientId.from(token), callbackData);
    }
}
