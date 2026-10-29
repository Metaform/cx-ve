package com.metaform.dxonboarding.domain.port;

import java.util.Optional;

/**
 * Checks that a participant's DID exists: that it resolves to its DID document. The participant's
 * resources, its DID among them, are provisioned before it onboards, and its credentials are later
 * offered to the CredentialService that document advertises.
 */
public interface DidResolver {

    /**
     * Resolves the DID. Never throws: what keeps it from resolving is the answer.
     *
     * @return why the DID does not resolve; empty when it does
     */
    Optional<String> problem(String did);
}
