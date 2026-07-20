package com.beardyinc.cxve.infrastructure.cfm.model;

import java.util.List;

public record DataspaceSpec(
        List<CredentialSpec> credentialSpecs,
        List<String> protocolStack
) {
}