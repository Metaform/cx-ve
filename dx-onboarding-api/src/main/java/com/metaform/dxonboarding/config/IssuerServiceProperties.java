package com.metaform.dxonboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The IssuerService's admin API, which approved participants are registered and offered their
 * credentials through ({@code issuer-service}).
 *
 * @param url                   the admin API's base URL (in-cluster: the issuer's admin port)
 * @param issuerContextId       the issuer's participant context, which holds the credential definitions
 * @param tokenResource         the jwtlet mapping the workload token is exchanged under — it must
 *                              grant {@code issuer-admin-api:admin}
 * @param credentialDefinitions the credential definitions a DECADE-X member is offered
 */
@ConfigurationProperties(prefix = "issuer-service")
public record IssuerServiceProperties(String url, String issuerContextId, String tokenResource,
                                      CredentialDefinitions credentialDefinitions) {

    /**
     * @param legalEntity the {@code DX_LegalEntity_VC}'s definition id — offered to every member
     * @param site        the {@code DX_Site_VC}'s definition id — offered to a member that declared a
     *                    business site
     */
    public record CredentialDefinitions(String legalEntity, String site) {
    }
}
