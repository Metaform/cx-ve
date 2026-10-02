package com.metaform.dxonboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This service's workload identity ({@code token}): the Kubernetes service-account token projected
 * into the pod, and the jwtlet it is exchanged at for a scoped token (RFC 8693).
 *
 * @param file     where the projected service-account token is mounted
 * @param exchange the jwtlet's token endpoint base URL and the audience to request
 */
@ConfigurationProperties(prefix = "token")
public record TokenProperties(File file, Exchange exchange) {

    public record File(String path) {
    }

    public record Exchange(String url, String audience) {
    }
}
