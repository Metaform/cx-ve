package com.metaform.cxve.adapter.in.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

/**
 * Derives the caller's client identity from the bearer token. For the OSP IdP's
 * client_credentials tokens (Ory Hydra) that is simply {@code sub}, which Hydra sets to the
 * client id. {@code act.sub} — the RFC 8693 actor an exchange-style issuer records for the
 * originating client — is preferred when present, so a future exchange-issued token still
 * resolves to the actual caller rather than a shared subject. This identity — never a request
 * parameter or body field — is what callback registrations are keyed on: a client can only ever
 * address its own registration.
 */
final class TokenClientId {

    private TokenClientId() {
    }

    static String from(Jwt token) {
        if (token.getClaim("act") instanceof Map<?, ?> act && act.get("sub") instanceof String actor && !actor.isBlank()) {
            return actor;
        }
        var subject = token.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AccessDeniedException("Token carries no caller identity (neither act.sub nor sub)");
        }
        return subject;
    }
}
