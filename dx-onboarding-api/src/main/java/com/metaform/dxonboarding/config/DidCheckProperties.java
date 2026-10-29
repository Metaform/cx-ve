package com.metaform.dxonboarding.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The check, at submit, that a request's {@code legalEntity.preferredDid} resolves
 * ({@code dx-onboarding.did-check}).
 *
 * @param enabled off: a request is submitted without resolving its DID, and a DID that does not
 *                resolve only shows when the credential offer fails after approval
 * @param scheme  the scheme did:web documents are fetched with — the platform's runtimes use
 *                {@code http} ({@code edc.iam.did.web.use.https=false})
 * @param timeout how long fetching a DID document may take, to connect and to answer each
 */
@ConfigurationProperties(prefix = "dx-onboarding.did-check")
public record DidCheckProperties(@DefaultValue("true") boolean enabled, @DefaultValue("http") String scheme,
                                 @DefaultValue("5s") Duration timeout) {
}
