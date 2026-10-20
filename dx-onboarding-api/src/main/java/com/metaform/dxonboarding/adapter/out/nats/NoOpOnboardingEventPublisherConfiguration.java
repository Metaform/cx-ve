package com.metaform.dxonboarding.adapter.out.nats;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingCompleted;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStarted;
import com.metaform.dxonboarding.domain.port.OnboardingEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A publisher that publishes nothing, when NATS is off — the exact complement of
 * {@link NatsConfiguration}'s condition, so there is always exactly one publisher.
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
                log.debug("onboarding request {} started (event publishing disabled)", event.processId());
            }

            @Override
            public void onboardingCompleted(OnboardingCompleted event) {
                log.debug("onboarding request {} ended {} (event publishing disabled)", event.processId(), event.state());
            }
        };
    }
}
