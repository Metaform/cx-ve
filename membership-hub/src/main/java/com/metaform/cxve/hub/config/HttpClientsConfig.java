package com.metaform.cxve.hub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The hub's outbound HTTP clients, one bean per downstream base URL. The dataspaces' onboarding
 * APIs are not here: each {@code DataspaceOnboarding} builds its own from {@code dataspaces.<id>}.
 */
@Configuration
public class HttpClientsConfig {

    @Value("${tenant-manager.url:http://cxve.localhost/api/tm}")
    private String tenantManagerUrl;

    @Value("${token.exchange.url:http://cxve.localhost/api/auth}")
    private String tokenExchangeUrl;

    @Bean
    public RestClient tenantManagerClient() {
        return RestClient.builder()
                .baseUrl(tenantManagerUrl)
                .build();
    }

    /** The platform's jwtlet (RFC 8693 token exchange), used for the Tenant Manager tokens. */
    @Bean
    public RestClient tokenExchangeClient() {
        return RestClient.builder()
                .baseUrl(tokenExchangeUrl)
                .build();
    }
}
