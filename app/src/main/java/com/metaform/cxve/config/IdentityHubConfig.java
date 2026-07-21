package com.metaform.cxve.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class IdentityHubConfig {
    @Value("${identityhub.url:http://jad.localhost/api/identity}")
    private String identityHubUrl;

    @Bean
    public RestClient identityHubWebClient() {
        return RestClient.builder()
                .baseUrl(identityHubUrl)
                .build();
    }
}

