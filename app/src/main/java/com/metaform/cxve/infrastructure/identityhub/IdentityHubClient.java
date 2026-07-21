package com.metaform.cxve.infrastructure.identityhub;

import com.metaform.cxve.infrastructure.identityhub.model.CredentialRequest;

public interface IdentityHubClient {
    CredentialRequest getCredentialRequest(String participantContextId, String holderProcessID);
}
