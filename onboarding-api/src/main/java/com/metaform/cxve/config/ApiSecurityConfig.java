package com.metaform.cxve.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;


/**
 * OAuth2 security for the administration API. {@code /api/**} is a JWT resource server: callers
 * — onboarding service providers, which are EXTERNAL parties — present a bearer token obtained
 * via client_credentials from the VE's OSP IdP (Ory Hydra; deliberately NOT the platform's
 * jwtlet, whose only grant exchanges Kubernetes ServiceAccount tokens — a workload-identity
 * mechanism that must not be handed to external clients). Validation is Boot's standard
 * property-driven decoder under {@code spring.security.oauth2.resourceserver.jwt}: signature via
 * the IdP's JWKS, {@code exp}/{@code nbf}, and {@code iss} against the configured issuer. Any
 * valid token may call the API; the one finer-grained rule is registering a status callback
 * ({@code POST .../registrationstatus/callback}), which requires the
 * {@code configure_partner_registration} scope — Hydra puts granted scopes in the {@code scp}
 * claim, which Spring maps to {@code SCOPE_} authorities out of the box.
 *
 * <p>Everything outside {@code /api/**} stays open: the actuator (Kubernetes probes) and the
 * springdoc/swagger surface.
 */
@Configuration
@EnableWebSecurity
public class ApiSecurityConfig {

    /**
     * The scope required to register a status callback — the one write that redirects onboarding
     * outcome data to a third party, hence gated separately (the Catena-X portal role of the same
     * name).
     */
    public static final String CONFIGURE_PARTNER_REGISTRATION = "SCOPE_configure_partner_registration";

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/administration/registrationstatus/callback")
                        .hasAuthority(CONFIGURE_PARTNER_REGISTRATION)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(loggingAuthenticationEntryPoint())
                        .accessDeniedHandler(loggingAccessDeniedHandler()));
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

    /** The default bearer 403 (insufficient_scope), plus a log line naming the caller and its scopes. */
    private static AccessDeniedHandler loggingAccessDeniedHandler() {
        var delegate = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            log.warn("403 {} {}: sub={}, authorities={}", request.getMethod(), request.getRequestURI(),
                    authentication == null ? "?" : authentication.getName(),
                    authentication == null ? "[]" : authentication.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority).toList());
            delegate.handle(request, response, exception);
        };
    }
}
