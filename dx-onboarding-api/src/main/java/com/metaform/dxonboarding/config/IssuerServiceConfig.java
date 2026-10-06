package com.metaform.dxonboarding.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The clients of the IssuerService's admin API and of the jwtlet that authorizes the calls to it. */
@Configuration
@EnableConfigurationProperties({IssuerServiceProperties.class, TokenProperties.class})
public class IssuerServiceConfig {

    @Bean
    @Qualifier("issuerServiceClient")
    public RestClient issuerServiceClient(IssuerServiceProperties properties) {
        return RestClient.builder().baseUrl(properties.url()).build();
    }

    @Bean
    @Qualifier("tokenExchangeClient")
    public RestClient tokenExchangeClient(TokenProperties properties) {
        return RestClient.builder().baseUrl(properties.exchange().url()).build();
    }
}
