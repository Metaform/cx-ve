package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.adapter.in.dto.RegistrationProcessView;
import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.domain.CancellationNotAllowedException;
import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import com.metaform.cxve.domain.model.RegistrationStatus;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CX-0009 §2.2.2 tenant registration — the fully OSP-mediated flow, where the OSP has
 * collected everything (consents included) and the tenant never touches this operator directly.
 * Runs the same onboarding as {@code /partnerregistration}; what differs is the contract — 201
 * with an empty body, and 409 when this OSP already used the externalId.
 *
 * <p>BEYOND-SPEC: the GET and DELETE mappings are cx-ve extensions (CX-0009 declares no read or
 * cancel path, leaving a lost callback unrecoverable): they serve any registration the calling
 * client submitted — through either flow, both store the submitter — keyed on the caller's token
 * identity, so a foreign externalId answers 404 exactly like an unknown one. Extension sites are
 * marked {@code BEYOND-SPEC} throughout the codebase.
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

    /**
     * BEYOND-SPEC: the recovery read — the registration's current wire status, exactly what the
     * status callbacks would have reported. 404 when the calling client has no such registration.
     */
    @GetMapping("/{externalId}")
    public RegistrationProcessView getRegistration(@PathVariable String externalId,
                                                   @AuthenticationPrincipal Jwt token) {
        return RegistrationProcessView.from(networkService.getRegistration(TokenClientId.from(token), externalId));
    }

    /** BEYOND-SPEC: every registration the calling client has submitted, optionally filtered by wire status. */
    @GetMapping
    public List<RegistrationProcessView> listRegistrations(@RequestParam(name = "status", required = false) RegistrationStatus status,
                                                           @AuthenticationPrincipal Jwt token) {
        return networkService.listRegistrations(TokenClientId.from(token)).stream()
                .map(RegistrationProcessView::from)
                .filter(view -> status == null || view.applicationStatus() == status)
                .toList();
    }

    /**
     * BEYOND-SPEC: cancels an in-flight registration — 204 on success, 404 for an unknown (or
     * foreign) externalId, 409 once the registration is terminal. No status callback is sent: the
     * caller initiated the cancellation, this response is the acknowledgment. A cancelled
     * registration frees its externalId — the same id may be resubmitted with corrected data.
     */
    @DeleteMapping("/{externalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRegistration(@PathVariable String externalId, @AuthenticationPrincipal Jwt token) {
        networkService.cancelRegistration(TokenClientId.from(token), externalId);
    }

    @ExceptionHandler(DuplicateRegistrationException.class)
    public ProblemDetail onDuplicateRegistration(DuplicateRegistrationException exception) {
        log.warn("Rejected tenant registration with 409: {}", exception.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(CancellationNotAllowedException.class)
    public ProblemDetail onCancellationNotAllowed(CancellationNotAllowedException exception) {
        log.warn("Rejected cancellation with 409: {}", exception.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail onUnknownRegistration(NoSuchElementException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }
}
