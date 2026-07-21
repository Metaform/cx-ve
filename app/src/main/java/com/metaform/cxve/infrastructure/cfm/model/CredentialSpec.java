package com.metaform.cxve.infrastructure.cfm.model;

public record CredentialSpec(
        String id,
        String type,
        String issuer,
        String format,
        String role) {
}
