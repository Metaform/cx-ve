package com.metaform.cxve.verification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The BFF's outbound HTTP clients, one bean per downstream base URL. */
@Configuration
public class HttpClientsConfig {

    @Value("${membership-hub.url:http://cxve.localhost/hub}")
    private String membershipHubUrl;

    @Value("${management-api.url:http://cxve.localhost/api/management/v5}")
    private String managementApiUrl;

    @Value("${certo.url:http://cxve.localhost/api/certo/management/v1}")
    private String certoUrl;

    @Value("${token.exchange.url:http://cxve.localhost/api/auth}")
    private String tokenExchangeUrl;

    @Bean
    public RestClient hubRestClient() {
        return RestClient.builder()
                .baseUrl(membershipHubUrl)
                .build();
    }

    @Bean
    public RestClient managementRestClient() {
        return RestClient.builder()
                .baseUrl(managementApiUrl)
                .build();
    }

    @Bean
    public RestClient certoRestClient() {
        return RestClient.builder()
                .baseUrl(certoUrl)
                .build();
    }

    /** The platform's jwtlet (RFC 8693 token exchange), used for management + certo tokens. */
    @Bean
    public RestClient tokenExchangeClient() {
        return RestClient.builder()
                .baseUrl(tokenExchangeUrl)
                .build();
    }
}
