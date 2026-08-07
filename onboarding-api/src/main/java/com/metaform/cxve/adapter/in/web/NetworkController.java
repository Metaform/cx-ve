package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/administration/registration/Network")
public class NetworkController {

    private final NetworkService networkService;

    public NetworkController(NetworkService networkService) {
        this.networkService = networkService;
    }

    /**
     * Registers a partner company (Authorization required - Roles: configure_partner_registration).
     */
    @PostMapping("/partnerRegistration")
    public void registerPartner(@RequestBody PartnerRegistrationData registrationData) {
        networkService.registerPartner(registrationData);
    }
}
