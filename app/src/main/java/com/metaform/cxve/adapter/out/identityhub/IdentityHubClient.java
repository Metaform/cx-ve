package com.metaform.cxve.adapter.out.identityhub;

import com.metaform.cxve.adapter.out.identityhub.model.CredentialRequest;

public interface IdentityHubClient {
    CredentialRequest getCredentialRequest(String participantContextId, String holderProcessID);
}
