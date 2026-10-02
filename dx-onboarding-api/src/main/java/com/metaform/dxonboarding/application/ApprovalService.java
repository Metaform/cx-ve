package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.DecadeXId;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.ReviewDecision;
import com.metaform.dxonboarding.domain.port.CredentialOfferService;
import com.metaform.dxonboarding.domain.port.HolderRegistrationService;
import com.metaform.dxonboarding.domain.port.OnboardingRequestRepository;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Carries out an approval — what makes an applicant a Decade-X member, the way the Catena-X
 * onboarding API does it for Catena-X:
 *
 * <ol>
 *   <li>the participant gets its Decade-X-ID: the one it supplied, if it is hosted by the VE (whose
 *       deployment needed it before the TSP would assign one), or else a newly assigned one;</li>
 *   <li>it is registered as a credential holder with the IssuerService, under its DID (its
 *       connector identity) and with its Decade-X-ID as claim;</li>
 *   <li>the IssuerService offers it the Decade-X credentials, pushed to its wallet.</li>
 * </ol>
 *
 * <p>The request is APPROVAL_IN_PROGRESS — its Decade-X-ID already visible — while that runs, and
 * APPROVED once the offer went out; a failing step leaves it APPROVAL_FAILED, which is final (there
 * is no operator to retry it). A Decade-X-ID another participant already holds rejects the request.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final OnboardingRequestRepository repository;
    private final HolderRegistrationService holders;
    private final CredentialOfferService credentialOffers;
    private final ReviewProperties properties;
    private final Clock clock;

    @Autowired
    public ApprovalService(OnboardingRequestRepository repository, HolderRegistrationService holders,
                           CredentialOfferService credentialOffers, ReviewProperties properties) {
        this(repository, holders, credentialOffers, properties, Clock.systemUTC());
    }

    ApprovalService(OnboardingRequestRepository repository, HolderRegistrationService holders,
                    CredentialOfferService credentialOffers, ReviewProperties properties, Clock clock) {
        this.repository = repository;
        this.holders = holders;
        this.credentialOffers = credentialOffers;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Approves the request, if it still awaits review. Never throws: the outcome is the request's
     * status.
     */
    public void approve(String requestId) {
        var inProgress = assignDecadeXId(requestId);
        if (inProgress.isEmpty()) {
            return;
        }
        var request = inProgress.get();
        try {
            holders.registerHolder(request);
            credentialOffers.offerCredentials(request);
        } catch (RuntimeException e) {
            log.error("onboarding request '{}' ({}): provisioning the approved participant failed", request.id(),
                    request.businessId(), e);
            repository.save(request.approvalFailed(clock.instant(), "Credential issuance failed: " + e.getMessage()));
            return;
        }
        repository.save(request.approved(clock.instant()));
        log.info("onboarding request '{}' ({}) APPROVED: {} holds Decade-X-ID {} and was offered its credentials",
                request.id(), request.businessId(), request.connectorId(), request.legalEntityId());
    }

    /**
     * Moves a request awaiting review to APPROVAL_IN_PROGRESS under its Decade-X-ID — or rejects it
     * when another participant holds the id it supplied. Synchronized: choosing an id and taking it
     * must not interleave with another approval doing the same.
     */
    private synchronized Optional<OnboardingRequest> assignDecadeXId(String requestId) {
        var request = repository.findById(requestId).orElse(null);
        if (request == null || request.status() != OnboardingStatus.SUBMITTED) {
            // nothing else decides a request today, but an approval must never overwrite a decision
            return Optional.empty();
        }
        var supplied = request.data().legalEntity().legalEntityId();
        String decadeXId;
        if (supplied != null && properties.hostedHere(request.connectorId())) {
            var holder = repository.findHolderOfLegalEntityId(supplied)
                    .filter(other -> !other.connectorId().equals(request.connectorId()));
            if (holder.isPresent()) {
                var reason = "Decade-X-ID %s is already held by another participant".formatted(supplied);
                log.warn("onboarding request '{}' ({}) REJECTED: {} (request '{}')", request.id(), request.businessId(),
                        reason, holder.get().id());
                repository.save(request.rejected(clock.instant(), ReviewDecision.RejectReasonCode.INVALID_LEGAL_ENTITY,
                        reason, true));
                return Optional.empty();
            }
            decadeXId = supplied;
        } else {
            if (supplied != null) {
                log.warn("onboarding request '{}' of '{}' supplied Decade-X-ID {} — ignored: only a participant hosted "
                        + "here keeps its own, every other one is assigned one", request.id(), request.connectorId(),
                        supplied);
            }
            decadeXId = unassignedDecadeXId();
        }
        var inProgress = request.approvalInProgress(decadeXId);
        repository.save(inProgress);
        log.info("onboarding request '{}' ({}) approved — provisioning {} as Decade-X-ID {}", request.id(),
                request.businessId(), request.connectorId(), decadeXId);
        return Optional.of(inProgress);
    }

    private String unassignedDecadeXId() {
        String candidate;
        do {
            candidate = DecadeXId.random();
        } while (repository.findHolderOfLegalEntityId(candidate).isPresent());
        return candidate;
    }
}
