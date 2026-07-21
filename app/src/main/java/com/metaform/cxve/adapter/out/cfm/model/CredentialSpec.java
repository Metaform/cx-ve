package com.metaform.cxve.adapter.out.cfm.model;

public record CredentialSpec(
        String id,
        String type,
        String issuer,
        String format,
        String role) {
}
