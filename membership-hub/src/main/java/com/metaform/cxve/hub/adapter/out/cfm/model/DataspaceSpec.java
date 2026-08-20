package com.metaform.cxve.hub.adapter.out.cfm.model;

import java.util.List;

public record DataspaceSpec(
        List<CredentialSpec> credentialSpecs,
        List<String> protocolStack
) {
}