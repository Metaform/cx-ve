package com.metaform.dxonboarding.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long a draft is kept without a change ({@code dx-onboarding.drafts}); how often expired
 * drafts are looked for is {@code dx-onboarding.drafts.purge-interval}.
 *
 * @param ttl a draft nobody changed for this long is deleted, with its documents
 */
@ConfigurationProperties(prefix = "dx-onboarding.drafts")
public record DraftProperties(@DefaultValue("30d") Duration ttl) {
}
