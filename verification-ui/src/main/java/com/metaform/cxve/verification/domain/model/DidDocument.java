package com.metaform.cxve.verification.domain.model;

/**
 * The parts of a resolved DID document this environment needs to talk to its subject: the DSP
 * {@code ProtocolEndpoint} it sends catalog, negotiation and transfer requests to, and the DCP
 * {@code CredentialService} the issuer delivers credential offers to. Both are advertised as
 * {@code service} entries; a participant missing either cannot complete an exchange, which is why
 * resolving the document is a verification step rather than a lookup.
 */
public record DidDocument(String did, String protocolEndpoint, String credentialServiceEndpoint) {
}
