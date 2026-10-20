package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.FieldError;
import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.ProblemException;
import com.metaform.dxonboarding.domain.model.onboarding.DecadeXId;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentUpload;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingDocument;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStarted;
import com.metaform.dxonboarding.domain.model.onboarding.SubmitCheck;
import com.metaform.dxonboarding.domain.model.onboarding.UploadedFile;
import com.metaform.dxonboarding.domain.model.onboarding.WriteCheck;
import com.metaform.dxonboarding.domain.port.DidResolver;
import com.metaform.dxonboarding.domain.port.DocumentStore;
import com.metaform.dxonboarding.domain.port.OnboardingEventPublisher;
import com.metaform.dxonboarding.domain.port.OnboardingRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Keeps the drafts and their documents, recognizes a create or a submit sent again, and files a
 * complete draft as {@code SUBMITTED} — announcing its start and taking it into review
 * ({@link AutomaticReview}). The review is not part of this API: the applicant reads its outcome
 * from the request's status.
 *
 * <p>Every change is synchronized: a version check, a quota or the DID's uniqueness must not
 * interleave with another change. Only resolving the DID happens outside the lock, as it leaves
 * the process.
 */
@Service
public class DefaultOnboardingRequestService implements OnboardingRequestService {

    /** How many drafts a connector may have at once. */
    static final int MAX_DRAFTS = 10;
    /** How many submitted requests awaiting a decision a connector may have at once. */
    static final int MAX_AWAITING_DECISION = 10;

    private static final Logger log = LoggerFactory.getLogger(DefaultOnboardingRequestService.class);

    private final OnboardingRequestRepository repository;
    private final DocumentStore documents;
    private final AutomaticReview review;
    private final OnboardingEventPublisher events;
    private final DidResolver didResolver;
    private final ReviewProperties reviewProperties;
    private final Clock clock;

    @Autowired
    public DefaultOnboardingRequestService(OnboardingRequestRepository repository, DocumentStore documents,
                                           AutomaticReview review, OnboardingEventPublisher events,
                                           DidResolver didResolver, ReviewProperties reviewProperties) {
        this(repository, documents, review, events, didResolver, reviewProperties, Clock.systemUTC());
    }

    DefaultOnboardingRequestService(OnboardingRequestRepository repository, DocumentStore documents,
                                    AutomaticReview review, OnboardingEventPublisher events, DidResolver didResolver,
                                    ReviewProperties reviewProperties, Clock clock) {
        this.repository = repository;
        this.documents = documents;
        this.review = review;
        this.events = events;
        this.didResolver = didResolver;
        this.reviewProperties = reviewProperties;
        this.clock = clock;
    }

    @Override
    public synchronized Created create(String connectorId, OnboardingRequestData data, String idempotencyKey) {
        var accepted = accepted(connectorId, data);
        var key = keyOrNull(idempotencyKey);
        var own = repository.findByConnector(connectorId);
        var earlier = own.stream()
                .filter(request -> key != null
                        ? key.equals(request.replay().createKey())
                        : request.replay().createKey() == null && request.replay().createdWith().equals(accepted))
                .findFirst();
        if (earlier.isPresent()) {
            if (!earlier.get().replay().createdWith().equals(accepted)) {
                throw new ProblemException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key '%s' was already used to create a request with different content".formatted(key));
            }
            log.info("onboarding request of '{}' is a replay of the create of '{}'", connectorId, earlier.get().id());
            return new Created(earlier.get(), true);
        }
        if (own.stream().filter(OnboardingRequest::isDraft).count() >= MAX_DRAFTS) {
            throw new ProblemException(ProblemCode.CONNECTOR_DRAFT_LIMIT_REACHED,
                    "The participant already has %d drafts: submit or delete one".formatted(MAX_DRAFTS));
        }
        var draft = OnboardingRequest.draft(UUID.randomUUID().toString(),
                "DX-OR-%06d".formatted(repository.nextBusinessNumber()), connectorId, accepted, key, clock.instant());
        repository.save(draft);
        log.info("onboarding request '{}' ({}) created as a draft by '{}'", draft.id(), draft.businessId(), connectorId);
        return new Created(draft, false);
    }

    @Override
    public OnboardingRequest get(String connectorId, String requestId) {
        return owned(connectorId, requestId);
    }

    @Override
    public synchronized OnboardingRequest replace(String connectorId, String requestId, long version,
                                                  OnboardingRequestData data) {
        var accepted = accepted(connectorId, data);
        var request = owned(connectorId, requestId);
        request.requireVersion(version);
        var replaced = request.withData(accepted, clock.instant());
        var stale = request.staleDocuments(accepted);
        stale.forEach(document -> documents.delete(document.documentId()));
        repository.save(replaced);
        log.info("onboarding request '{}' ({}) corrected{}", request.id(), request.businessId(), stale.isEmpty() ? ""
                : ", dropping the signed copies that no longer match: " + stale.stream().map(this::describe).toList());
        return replaced;
    }

    @Override
    public synchronized void delete(String connectorId, String requestId, long version) {
        var request = owned(connectorId, requestId);
        request.requireVersion(version);
        request.requireDraft();
        deleteDraft(request);
        log.info("onboarding request '{}' ({}) deleted by '{}'", request.id(), request.businessId(), connectorId);
    }

    @Override
    public synchronized DocumentChange addDocument(String connectorId, String requestId, long version,
                                                   DocumentUpload upload) {
        var request = owned(connectorId, requestId);
        request.requireVersion(version);
        var documentId = UUID.randomUUID();
        var changed = request.withDocument(documentId, upload, clock.instant());
        documents.store(documentId, upload.file().content());
        repository.save(changed);
        var document = changed.document(documentId).orElseThrow();
        log.info("onboarding request '{}' ({}): {} added", request.id(), request.businessId(), describe(document));
        return new DocumentChange(changed, document);
    }

    @Override
    public synchronized DocumentChange replaceDocument(String connectorId, String requestId, UUID documentId,
                                                       long version, String label, UploadedFile file) {
        var request = owned(connectorId, requestId);
        request.requireVersion(version);
        var changed = request.withReplacedFile(documentId, label, file, clock.instant());
        documents.store(documentId, file.content());
        repository.save(changed);
        var document = changed.document(documentId).orElseThrow();
        log.info("onboarding request '{}' ({}): {} replaced", request.id(), request.businessId(), describe(document));
        return new DocumentChange(changed, document);
    }

    @Override
    public synchronized OnboardingRequest removeDocument(String connectorId, String requestId, UUID documentId,
                                                         long version) {
        var request = owned(connectorId, requestId);
        request.requireVersion(version);
        var changed = request.withoutDocument(documentId, clock.instant());
        documents.delete(documentId);
        repository.save(changed);
        log.info("onboarding request '{}' ({}): document {} removed", request.id(), request.businessId(), documentId);
        return changed;
    }

    @Override
    public synchronized DocumentFile download(String connectorId, String requestId, UUID documentId) {
        var request = owned(connectorId, requestId);
        var document = request.document(documentId)
                .orElseThrow(() -> ProblemException.notFound("No document " + documentId));
        var content = documents.load(documentId)
                .orElseThrow(() -> ProblemException.notFound("No file for document " + documentId));
        return new DocumentFile(document, content);
    }

    @Override
    public OnboardingRequest submit(String connectorId, String requestId, long version, String idempotencyKey) {
        // the DID is resolved before taking the lock, as it leaves the process — and again under
        // it only when a correction changed the DID in the meantime
        var snapshot = owned(connectorId, requestId);
        var resolution = snapshot.isDraft()
                ? snapshot.data().preferredDid().map(did -> new DidResolution(did, didResolver.problem(did))).orElse(null)
                : null;
        var outcome = file(connectorId, requestId, version, keyOrNull(idempotencyKey), resolution);
        if (outcome.newlySubmitted()) {
            var request = outcome.request();
            // announced before the review takes it on, so an observer sees the start before the outcome
            events.onboardingStarted(new OnboardingStarted(request.id(), request.data().applicantReference(),
                    connectorId));
            review.submitted(request);
        }
        return outcome.request();
    }

    /**
     * Deletes the drafts nobody changed since the given instant, with their documents.
     *
     * @return how many were deleted
     */
    public synchronized int deleteDraftsUntouchedSince(Instant cutoff) {
        var expired = repository.findDraftsUpdatedBefore(cutoff);
        expired.forEach(this::deleteDraft);
        expired.forEach(draft -> log.info("onboarding request '{}' ({}) of '{}' deleted: a draft untouched since {}",
                draft.id(), draft.businessId(), draft.connectorId(), draft.updatedAt()));
        return expired.size();
    }

    private record DidResolution(String did, Optional<String> problem) {
    }

    private record Submitted(OnboardingRequest request, boolean newlySubmitted) {
    }

    private synchronized Submitted file(String connectorId, String requestId, long version, String key,
                                        DidResolution resolution) {
        var request = owned(connectorId, requestId);
        if (key != null && repository.findByConnector(connectorId).stream()
                .anyMatch(other -> key.equals(other.replay().submitKey()) && !other.id().equals(requestId))) {
            throw new ProblemException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key '%s' was already used to submit another request".formatted(key));
        }
        if (!request.isDraft()) {
            log.info("onboarding request '{}' ({}) was already submitted, now {}: returned as it is", request.id(),
                    request.businessId(), request.status());
            return new Submitted(request, false);
        }
        request.requireVersion(version);
        var errors = new ArrayList<>(SubmitCheck.violations(request));
        request.data().preferredDid().ifPresent(did -> errors.addAll(didViolations(request, did, resolution)));
        if (!errors.isEmpty()) {
            log.info("onboarding request '{}' ({}) cannot be submitted: {}", request.id(), request.businessId(), errors);
            throw ProblemException.submitRejected(errors);
        }
        if (repository.findByConnector(connectorId).stream().filter(other -> other.status().awaitsDecision()).count()
                >= MAX_AWAITING_DECISION) {
            throw new ProblemException(ProblemCode.CONNECTOR_PENDING_REQUEST_LIMIT_REACHED,
                    ("The participant already has %d submitted requests awaiting a decision: submit this one again "
                            + "once one of them is approved or rejected").formatted(MAX_AWAITING_DECISION));
        }
        var submitted = request.submitted(clock.instant(), key);
        repository.save(submitted);
        log.info("onboarding request '{}' ({}) submitted by '{}' for {}", submitted.id(), submitted.businessId(),
                connectorId, submitted.data().legalEntity().legalName());
        return new Submitted(submitted, true);
    }

    /** The DID must be new to the TSP — held by no other request in progress or approved — and resolve. */
    private List<FieldError> didViolations(OnboardingRequest request, String did, DidResolution resolution) {
        var errors = new ArrayList<FieldError>();
        if (repository.findByPreferredDid(did).stream()
                .anyMatch(other -> !other.id().equals(request.id()) && other.status().holdsDid())) {
            errors.add(new FieldError("legalEntity.preferredDid", "already in use"));
        }
        var problem = resolution != null && resolution.did().equals(did) ? resolution.problem() : didResolver.problem(did);
        problem.ifPresent(why -> errors.add(new FieldError("legalEntity.preferredDid", "does not resolve: " + why)));
        return errors;
    }

    /**
     * The data as it is kept: refused when it breaks the schema's bounds, its DID normalized — and
     * its {@code legalEntity.legalEntityId} kept only from a participant hosted here, whose
     * deployment carries that DECADE-X-ID already. Anybody else's is ignored: the TSP assigns it.
     */
    private OnboardingRequestData accepted(String connectorId, OnboardingRequestData data) {
        var errors = new ArrayList<>(WriteCheck.violations(data));
        var declared = data == null ? Optional.<String>empty() : data.declaredLegalEntityId();
        var hosted = reviewProperties.hostedHere(connectorId);
        if (hosted && declared.isPresent() && !declared.get().matches(DecadeXId.PATTERN)) {
            errors.add(new FieldError("legalEntity.legalEntityId", "not a DECADE-X-ID (DX- and 8 digits)"));
        }
        if (!errors.isEmpty()) {
            log.info("onboarding request data of '{}' refused: {}", connectorId, errors);
            throw ProblemException.invalidRequest(errors);
        }
        if (declared.isPresent() && !hosted) {
            log.warn("onboarding request data of '{}': legalEntity.legalEntityId '{}' ignored — the TSP assigns the "
                    + "DECADE-X-ID on approval", connectorId, declared.get());
            return data.withoutLegalEntityId().normalized();
        }
        return data.normalized();
    }

    private OnboardingRequest owned(String connectorId, String requestId) {
        return repository.findById(requestId)
                .filter(request -> request.connectorId().equals(connectorId))
                .orElseThrow(() -> ProblemException.notFound("No onboarding request " + requestId));
    }

    private void deleteDraft(OnboardingRequest draft) {
        draft.documents().forEach(document -> documents.delete(document.documentId()));
        repository.delete(draft.id());
    }

    private String describe(OnboardingDocument document) {
        var what = document.type().wireName() + (document.useCaseId() == null ? "" : " for " + document.useCaseId().id());
        return "%s %s ('%s', %d bytes)".formatted(what, document.documentId(), document.filename(), document.sizeBytes());
    }

    private static String keyOrNull(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
    }
}
