package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.domain.model.CallbackRequestData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/administration/RegistrationStatus")
public class RegistrationStatusController {

    private final RegistrationStatusService registrationStatusService;

    public RegistrationStatusController(RegistrationStatusService registrationStatusService) {
        this.registrationStatusService = registrationStatusService;
    }

    /**
     * Gets the callback address the onboarding service provider with the given client id has
     * registered; without a clientId, the anonymous registration (Authorization required -
     * Roles: configure_partner_registration).
     */
    @GetMapping("/callback")
    public CallbackRequestData getCallbackAddress(@RequestParam(required = false) String clientId) {
        return registrationStatusService.getCallbackAddress(clientId);
    }

    /**
     * Sets the callback address of the onboarding service provider (Authorization required - Roles: configure_partner_registration).
     */
    @PostMapping("/callback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCallbackAddress(@RequestBody CallbackRequestData callbackData) {
        registrationStatusService.setCallbackAddress(callbackData);
    }
}
