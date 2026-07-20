package com.beardyinc.cxve.infrastructure.identityhub.model;

import java.util.List;

public record CredentialRequest(String issuerDid, String holderPid, String issuerPid, String status,
                                         List<DesiredCredential> typesAndFormats) {
}


