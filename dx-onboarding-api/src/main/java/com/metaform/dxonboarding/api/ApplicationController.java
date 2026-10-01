package com.metaform.dxonboarding.api;

import com.metaform.dxonboarding.application.ApplicationService;
import com.metaform.dxonboarding.domain.ApplicationRecord;
import com.metaform.dxonboarding.domain.MembershipApplication;
import com.metaform.dxonboarding.domain.Webhook;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Decade-X onboarding API (stub): submit an application, read it back, and set the webhook its
 * decision is delivered to. Every call is the onboarding service provider's, identified by its
 * token's subject — applications and webhooks are scoped to it.
 */
@RestController
@RequestMapping("/api/v1")
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    /** Accepted for decision; the decision arrives at the webhook. */
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Receipt submit(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody MembershipApplication application) {
        return new Receipt(service.submit(caller.getSubject(), application).applicationId());
    }

    @GetMapping("/applications/{applicationId}")
    public ApplicationRecord get(@AuthenticationPrincipal Jwt caller, @PathVariable String applicationId) {
        return service.get(caller.getSubject(), applicationId);
    }

    @PutMapping("/webhook")
    public ResponseEntity<Void> setWebhook(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody Webhook webhook) {
        service.registerWebhook(caller.getSubject(), webhook);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/webhook")
    public ResponseEntity<Webhook> getWebhook(@AuthenticationPrincipal Jwt caller) {
        return ResponseEntity.of(service.webhook(caller.getSubject()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }

    public record Receipt(String applicationId) {
    }
}
