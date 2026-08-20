package com.metaform.cxve.hub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The hub's outbound HTTP clients, one bean per downstream base URL. */
@Configuration
public class HttpClientsConfig {

    @Value("${onboarding-api.url:http://cxve.localhost/onboarding}")
    private String onboardingApiUrl;

    @Value("${onboarding-api.auth.token-url:http://cxve.localhost/auth/osp/oauth2/token}")
    private String ospTokenUrl;

    @Value("${tenant-manager.url:http://cxve.localhost/api/tm}")
    private String tenantManagerUrl;

    @Value("${token.exchange.url:http://cxve.localhost/api/auth}")
    private String tokenExchangeUrl;

    // "...RestClient", not "onboardingApiClient": that name belongs to the @Service consuming
    // this bean, and Spring refuses two definitions under one name.
    @Bean
    public RestClient onboardingApiRestClient() {
        return RestClient.builder()
                .baseUrl(onboardingApiUrl)
                .build();
    }

    /** The OSP IdP's token endpoint (Ory Hydra), for the client-credentials grant. */
    @Bean
    public RestClient ospTokenClient() {
        return RestClient.builder()
                .baseUrl(ospTokenUrl)
                .build();
    }

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
