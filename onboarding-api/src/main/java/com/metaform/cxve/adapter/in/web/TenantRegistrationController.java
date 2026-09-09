package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CX-0009 §2.2.2 tenant registration: the fully OSP-mediated flow, where the OSP has
 * collected everything (consents included) and the tenant never touches this operator directly.
 * Runs the same onboarding as {@code /partnerregistration}; what differs is the contract — 201
 * with an empty body (the OSP correlates on its own externalId via the status callbacks; the
 * spec declares no read endpoint), and 409 when this OSP already used the externalId.
 */
@RestController
@RequestMapping("/api/administration/osp/v2/tenant-registration")
public class TenantRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistrationController.class);

    private final NetworkService networkService;

    public TenantRegistrationController(NetworkService networkService) {
        this.networkService = networkService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void registerTenant(@Valid @RequestBody OspTenantRegistrationData tenantData,
                               @AuthenticationPrincipal Jwt token) {
        networkService.registerTenant(TokenClientId.from(token), tenantData);
    }

    @ExceptionHandler(DuplicateRegistrationException.class)
    public ProblemDetail onDuplicateRegistration(DuplicateRegistrationException exception) {
        log.warn("Rejected tenant registration with 409: {}", exception.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
