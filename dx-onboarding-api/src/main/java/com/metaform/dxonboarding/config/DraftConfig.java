package com.metaform.dxonboarding.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The draft settings, and the scheduling the purge of expired drafts runs on. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DraftProperties.class)
public class DraftConfig {
}
