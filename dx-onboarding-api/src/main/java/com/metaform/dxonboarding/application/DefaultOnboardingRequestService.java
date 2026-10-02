package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.domain.InvalidOnboardingRequestException;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentRef;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.Submission;
import com.metaform.dxonboarding.domain.port.DocumentStore;
import com.metaform.dxonboarding.domain.port.OnboardingRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Validates a submission as a whole, recognizes a retry by its content, and files a new request
 * with its documents as {@code SUBMITTED}. The review that moves it on is a TSP operator's and not
 * part of this API.
 */
@Service
public class DefaultOnboardingRequestService implements OnboardingRequestService {

    private static final Logger log = LoggerFactory.getLogger(DefaultOnboardingRequestService.class);

    private final OnboardingRequestRepository repository;
    private final DocumentStore documents;
    private final Clock clock;

    @Autowired
    public DefaultOnboardingRequestService(OnboardingRequestRepository repository, DocumentStore documents) {
        this(repository, documents, Clock.systemUTC());
    }

    DefaultOnboardingRequestService(OnboardingRequestRepository repository, DocumentStore documents, Clock clock) {
        this.repository = repository;
        this.documents = documents;
        this.clock = clock;
    }

    // synchronized: the retry check and the filing must not interleave, or a retry sent while the
    // original is being filed would create a second request
    @Override
    public synchronized OnboardingRequest submit(String connectorId, Submission submission) {
        var violations = submission.violations();
        if (!violations.isEmpty()) {
            log.info("onboarding request of '{}' refused: {}", connectorId, violations);
            throw new InvalidOnboardingRequestException(violations);
        }
        var fingerprint = submission.fingerprint();
        var original = repository.findByFingerprint(connectorId, fingerprint);
        if (original.isPresent()) {
            log.info("onboarding request of '{}' is a retry of '{}'", connectorId, original.get().id());
            return original.get();
        }

        var ucaDocuments = new LinkedHashMap<String, DocumentRef>();
        submission.request().ucas().forEach(uca -> ucaDocuments.put(uca.useCaseId(),
                store(submission, Submission.ucaDocumentPart(uca.useCaseId()))));
        var request = new OnboardingRequest(
                UUID.randomUUID().toString(),
                "DX-OR-%06d".formatted(repository.count() + 1),
                connectorId,
                Instant.now(clock),
                OnboardingStatus.SUBMITTED,
                submission.request(),
                fingerprint,
                null,
                store(submission, Submission.GTC_DOCUMENT),
                store(submission, Submission.REGISTRATION_EXTRACT_DOCUMENT),
                store(submission, Submission.POWER_OF_ATTORNEY_DOCUMENT),
                ucaDocuments,
                null);
        repository.save(request);
        log.info("onboarding request '{}' ({}) submitted by '{}' for {}", request.id(), request.businessId(),
                connectorId, request.data().legalEntity().legalName());
        return request;
    }

    @Override
    public OnboardingRequest get(String connectorId, String requestId) {
        return repository.findById(requestId)
                .filter(request -> request.connectorId().equals(connectorId))
                .orElseThrow(() -> new NoSuchElementException("No onboarding request " + requestId));
    }

    /** The part's document, stored; null when the submission has no such part. */
    private DocumentRef store(Submission submission, String part) {
        return submission.document(part).map(documents::store).orElse(null);
    }
}
