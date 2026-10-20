package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.config.DraftProperties;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the drafts left untouched for {@code dx-onboarding.drafts.ttl} (30 days), with their documents. */
@Component
public class DraftExpiry {

    private final DefaultOnboardingRequestService service;
    private final DraftProperties properties;
    private final Clock clock = Clock.systemUTC();

    public DraftExpiry(DefaultOnboardingRequestService service, DraftProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${dx-onboarding.drafts.purge-interval:PT1H}",
            fixedDelayString = "${dx-onboarding.drafts.purge-interval:PT1H}")
    public void purge() {
        service.deleteDraftsUntouchedSince(clock.instant().minus(properties.ttl()));
    }
}
