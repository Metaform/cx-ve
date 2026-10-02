package com.metaform.dxonboarding.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The IssuerService's admin API, which approved participants are registered and offered their
 * credentials through ({@code issuer-service}).
 *
 * @param url                     the admin API's base URL (in-cluster: the issuer's admin port)
 * @param issuerContextId         the issuer's participant context, which holds the credential definitions
 * @param tokenResource           the jwtlet mapping the workload token is exchanged under — it must
 *                                grant {@code issuer-admin-api:admin}
 * @param credentialDefinitionIds the credential definitions a Decade-X member is offered
 */
@ConfigurationProperties(prefix = "issuer-service")
public record IssuerServiceProperties(String url, String issuerContextId, String tokenResource,
                                      List<String> credentialDefinitionIds) {

    public IssuerServiceProperties {
        credentialDefinitionIds = credentialDefinitionIds == null ? List.of() : List.copyOf(credentialDefinitionIds);
    }
}
