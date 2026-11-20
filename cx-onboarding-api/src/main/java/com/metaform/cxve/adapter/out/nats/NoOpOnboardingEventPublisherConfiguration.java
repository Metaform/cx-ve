package com.metaform.cxve.adapter.out.nats;

import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingStarted;
import com.metaform.cxve.domain.port.OnboardingEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a do-nothing publisher when NATS is off, so onboarding runs unchanged with no broker
 * (local dev, tests).
 *
 * <p>The condition is the exact complement of {@link NatsConfiguration}'s rather than
 * {@code @ConditionalOnMissingBean}: the latter depends on the order user-defined configurations
 * are evaluated in, which is not guaranteed, and losing that race either way would leave the
 * application context with no publisher at all or with two.
 */
@Configuration
@ConditionalOnProperty(prefix = "nats", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpOnboardingEventPublisherConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NoOpOnboardingEventPublisherConfiguration.class);

    @Bean
    public OnboardingEventPublisher onboardingEventPublisher() {
        log.info("NATS is disabled — onboarding events will not be published");
        return new OnboardingEventPublisher() {
            @Override
            public void onboardingStarted(OnboardingStarted event) {
                log.debug("Onboarding {} started (event publishing disabled)", event.processId());
            }

            @Override
            public void onboardingCompleted(OnboardingCompleted event) {
                log.debug("Onboarding {} completed (event publishing disabled)", event.processId());
            }
        };
    }
}
