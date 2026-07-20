package com.beardyinc.cxve.infrastructure.identityhub;

import com.beardyinc.cxve.infrastructure.identityhub.model.CredentialRequest;

public interface IdentityHubClient {
    CredentialRequest getCredentialRequest(String participantContextId, String holderProcessID);
}
