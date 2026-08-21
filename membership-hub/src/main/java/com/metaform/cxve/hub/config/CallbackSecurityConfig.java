package com.metaform.cxve.hub.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 security for the status-callback endpoint: {@code /api/callbacks/**} is a JWT resource
 * server. The caller is the Onboarding API, which obtained its bearer via client_credentials from
 * the VE's OSP IdP (Ory Hydra) using the client id/secret this app registered ALONGSIDE its
 * callback URL — see {@code OnboardingApiClient#registerCallback}. Validation is Boot's standard
 * property-driven decoder under {@code spring.security.oauth2.resourceserver.jwt}: signature via
 * the IdP's JWKS, {@code exp}/{@code nbf}, and {@code iss} against the configured issuer.
 *
 * <p>Everything else stays open: the members API is the unauthenticated operator surface (see the
 * chart's httpRoute note), and the actuator serves the Kubernetes probes.
 */
@Configuration
@EnableWebSecurity
public class CallbackSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(CallbackSecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain callbackChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/callbacks/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(loggingAuthenticationEntryPoint()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /** The default bearer 401 (WWW-Authenticate with error details), plus a log line saying why. */
    private static AuthenticationEntryPoint loggingAuthenticationEntryPoint() {
        var delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            log.warn("401 {} {}: {}", request.getMethod(), request.getRequestURI(),
                    exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "no bearer token" : exception.getMessage());
            delegate.commence(request, response, exception);
        };
    }
}
