package com.beardyinc.cxve.interfaces.rest;

import com.beardyinc.cxve.application.RegistrationStatusService;
import com.beardyinc.cxve.domain.model.OnboardingServiceProviderCallbackRequestData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
     * Gets the callback address of the onboarding service provider (Authorization required - Roles: configure_partner_registration).
     */
    @GetMapping("/callback")
    public OnboardingServiceProviderCallbackRequestData getCallbackAddress() {
        return registrationStatusService.getCallbackAddress();
    }

    /**
     * Sets the callback address of the onboarding service provider (Authorization required - Roles: configure_partner_registration).
     */
    @PostMapping("/callback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCallbackAddress(@RequestBody OnboardingServiceProviderCallbackRequestData callbackData) {
        registrationStatusService.setCallbackAddress(callbackData);
    }
}
