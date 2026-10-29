package com.metaform.dxonboarding.config;

import com.metaform.dxonboarding.adapter.out.did.DidWebResolver;
import com.metaform.dxonboarding.domain.port.DidResolver;
import java.net.http.HttpClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The DID check at submit: a did:web resolver, or — switched off — one that lets every DID pass. */
@Configuration
@EnableConfigurationProperties(DidCheckProperties.class)
public class DidCheckConfig {

    private static final Logger log = LoggerFactory.getLogger(DidCheckConfig.class);

    @Bean
    public DidResolver didResolver(DidCheckProperties properties) {
        if (!properties.enabled()) {
            log.warn("The DID check is off (dx-onboarding.did-check.enabled): requests are submitted without "
                    + "resolving their preferredDid");
            return did -> Optional.empty();
        }
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return new DidWebResolver(RestClient.builder().requestFactory(requestFactory).build(), properties.scheme());
    }
}
