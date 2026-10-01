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
 * the IdP's JWKS, {@code exp}/{@code nbf}, and {@code iss} against the configured issuer. On top
 * of authentication, every administration endpoint is authorized per operation class: the
 * fine-grained scope of its class ({@code registration:write}, {@code registration:read},
 * {@code callback-config:write}, {@code callback-config:read} — least privilege, so a leaked
 * high-volume automation credential cannot also redirect status delivery), OR the
 * {@code configure_partner_registration} umbrella — the Required Role CX-0009 declares on each
 * CSP-B endpoint, kept sufficient for spec conformance. Hydra puts granted scopes in the
 * {@code scp} claim, which Spring maps to {@code SCOPE_} authorities out of the box.
 *
 * <p>Everything outside {@code /api/**} stays open: the actuator (Kubernetes probes) and the
 * springdoc/swagger surface.
 */
@Configuration
@EnableWebSecurity
public class ApiSecurityConfig {

    /**
     * The umbrella scope sufficient for the whole administration surface — CX-0009 requires the
     * role of the same name (the Catena-X portal role) on every CSP-B endpoint.
     */
    public static final String CONFIGURE_PARTNER_REGISTRATION = "SCOPE_configure_partner_registration";

    // BEYOND-SPEC: the four fine-grained scopes below are cx-ve extensions — CX-0009 knows only
    // the configure_partner_registration role; these give least-privilege clients an alternative.

    /** BEYOND-SPEC: create registrations (both flows) and cancel them. */
    public static final String REGISTRATION_WRITE = "SCOPE_registration:write";

    /** BEYOND-SPEC: read registration status — the polling/recovery client's scope. */
    public static final String REGISTRATION_READ = "SCOPE_registration:read";

    /** BEYOND-SPEC: set or replace the status-callback configuration (carries the OSP's client secret). */
    public static final String CALLBACK_CONFIG_WRITE = "SCOPE_callback-config:write";

    /** BEYOND-SPEC: read the (secret-free) status-callback configuration. */
    public static final String CALLBACK_CONFIG_READ = "SCOPE_callback-config:read";

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,
                                "/api/administration/registration/network/partnerregistration",
                                "/api/administration/osp/v2/tenant-registration")
                        .hasAnyAuthority(CONFIGURE_PARTNER_REGISTRATION, REGISTRATION_WRITE)
                        .requestMatchers(HttpMethod.DELETE, "/api/administration/osp/v2/tenant-registration/*")
                        .hasAnyAuthority(CONFIGURE_PARTNER_REGISTRATION, REGISTRATION_WRITE)
                        .requestMatchers(HttpMethod.GET,
                                "/api/administration/osp/v2/tenant-registration",
                                "/api/administration/osp/v2/tenant-registration/*")
                        .hasAnyAuthority(CONFIGURE_PARTNER_REGISTRATION, REGISTRATION_READ)
                        .requestMatchers(HttpMethod.POST, "/api/administration/registrationstatus/callback")
                        .hasAnyAuthority(CONFIGURE_PARTNER_REGISTRATION, CALLBACK_CONFIG_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/administration/registrationstatus/callback")
                        .hasAnyAuthority(CONFIGURE_PARTNER_REGISTRATION, CALLBACK_CONFIG_READ)
                        // Anything else under the administration surface: umbrella only, so a new
                        // endpoint is never accidentally open to a fine-grained scope.
                        .requestMatchers("/api/administration/**").hasAuthority(CONFIGURE_PARTNER_REGISTRATION)
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
