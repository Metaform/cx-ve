package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.domain.ApplicationRecord;
import com.metaform.dxonboarding.domain.Decision;
import com.metaform.dxonboarding.domain.MembershipApplication;
import com.metaform.dxonboarding.domain.Webhook;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Receives applications and decides them — the stub's whole business. An application is accepted
 * on receipt (the caller gets its id) and decided ASYNCHRONOUSLY, after
 * {@code dx-onboarding.decision-delay}: approved, unless a live (not rejected) application already
 * holds its Decade-X-ID or DID. The decision goes to the submitter's webhook; a submitter without
 * one can still read it back.
 *
 * <p>In memory only: a restart forgets everything, which is fine for a stub that exists to give
 * the Membership Hub a second, differently shaped onboarding API to talk to.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final Map<String, ApplicationRecord> applications = new ConcurrentHashMap<>();
    private final Map<String, Webhook> webhooks = new ConcurrentHashMap<>();
    private final WebhookSender sender;
    private final Executor decisionExecutor;
    private final Duration decisionDelay;

    public ApplicationService(WebhookSender sender,
                              @Qualifier("decisionExecutor") Executor decisionExecutor,
                              @Value("${dx-onboarding.decision-delay:0s}") Duration decisionDelay) {
        this.sender = sender;
        this.decisionExecutor = decisionExecutor;
        this.decisionDelay = decisionDelay;
    }

    /** Registers (or replaces) the submitter's decision webhook. */
    public void registerWebhook(String submitter, Webhook webhook) {
        webhooks.put(submitter, webhook);
        log.info("webhook of '{}' set to {}", submitter, webhook.url());
    }

    public Optional<Webhook> webhook(String submitter) {
        return Optional.ofNullable(webhooks.get(submitter));
    }

    /** Accepts the application and schedules its decision; returns the assigned application id. */
    public ApplicationRecord submit(String submitter, MembershipApplication application) {
        var record = new ApplicationRecord(UUID.randomUUID().toString(), submitter, application, Instant.now(), null);
        applications.put(record.applicationId(), record);
        log.info("application '{}' received from '{}': {} ({}, {})", record.applicationId(), submitter,
                application.legalName(), application.decadeXId(), application.did());
        decisionExecutor.execute(() -> decide(record.applicationId()));
        return record;
    }

    /** @throws NoSuchElementException for an application this submitter did not submit */
    public ApplicationRecord get(String submitter, String applicationId) {
        var record = applications.get(applicationId);
        if (record == null || !record.submitter().equals(submitter)) {
            throw new NoSuchElementException("No application " + applicationId);
        }
        return record;
    }

    private void decide(String applicationId) {
        try {
            if (!decisionDelay.isZero()) {
                Thread.sleep(decisionDelay.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        var record = applications.get(applicationId);
        var application = record.application();
        var conflict = applications.values().stream()
                .filter(other -> !other.applicationId().equals(applicationId))
                .filter(other -> other.decision() == null || other.decision().decision() == Decision.Outcome.APPROVED)
                .filter(other -> other.receivedAt().isBefore(record.receivedAt()))
                .filter(other -> Objects.equals(other.application().decadeXId(), application.decadeXId())
                        || Objects.equals(other.application().did(), application.did()))
                .findFirst();
        var decision = conflict
                .map(other -> Decision.rejected(application.applicationRef(), applicationId,
                        "Decade-X-ID or DID already held by application " + other.applicationId()))
                .orElseGet(() -> Decision.approved(application.applicationRef(), applicationId));
        applications.put(applicationId, record.decided(decision));
        log.info("application '{}' {}{}", applicationId, decision.decision(),
                decision.reason() == null ? "" : ": " + decision.reason());
        var webhook = webhooks.get(record.submitter());
        if (webhook == null) {
            log.warn("application '{}': submitter '{}' has no webhook — the decision is only readable", applicationId,
                    record.submitter());
            return;
        }
        try {
            sender.send(webhook, decision);
        } catch (RuntimeException e) {
            log.error("application '{}': delivering the decision to {} failed", applicationId, webhook.url(), e);
        }
    }
}
