package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.adapter.out.document.InMemoryDocumentStore;
import com.metaform.dxonboarding.adapter.out.persistence.InMemoryOnboardingRequestRepository;
import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.FieldError;
import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentType;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStarted;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.UseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static com.metaform.dxonboarding.domain.model.onboarding.Requests.NOW;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.data;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.declaring;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.gtc;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.other;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.pdfFile;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.problem;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.uca;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withApplicantReference;
import static com.metaform.dxonboarding.domain.model.onboarding.Requests.withGtc;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The intake's rules across requests: drafts recognized when created again, documents kept with
 * their drafts, a submit that checks the DID across every request and the quotas, and requests
 * scoped to the connector that created them.
 */
class DefaultOnboardingRequestServiceTest {

    private static final String HOSTED_PREFIX = "did:web:identity.cxve.localhost:";
    private static final String HOSTED = HOSTED_PREFIX + "verification-participant-dx";
    private static final String ACME = "did:web:acme.example.com";
    private static final String OTHER = "did:web:other.example.com";

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryOnboardingRequestRepository repository = new InMemoryOnboardingRequestRepository();
    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    /** The requests taken into review; nothing is approved here — AutomaticReviewTest covers that. */
    private final List<String> reviewed = new ArrayList<>();
    private final ReviewProperties reviewProperties =
            new ReviewProperties(HOSTED_PREFIX, new ReviewProperties.AutoApprove(false, false), null);
    private final RecordingEvents events = new RecordingEvents();
    private final AutomaticReview review = new AutomaticReview(null, reviewProperties, Runnable::run) {
        @Override
        public void submitted(OnboardingRequest request) {
            reviewed.add(request.id());
        }
    };
    /** Why a DID does not resolve, by DID; a DID not listed resolves. */
    private final Map<String, String> unresolvable = new HashMap<>();
    private final List<String> resolved = new ArrayList<>();
    private final DefaultOnboardingRequestService service = new DefaultOnboardingRequestService(repository, documents,
            review, events, did -> {
                resolved.add(did);
                return Optional.ofNullable(unresolvable.get(did));
            }, reviewProperties, clock);

    /** A complete draft of the connector, with its GTC and UCA. */
    private OnboardingRequest complete(String connectorId) {
        var draft = service.create(connectorId, data(connectorId, UseCase.PRODUCT_CONFORMITY), null).request();
        draft = service.addDocument(connectorId, draft.id(), draft.version(), gtc()).request();
        return service.addDocument(connectorId, draft.id(), draft.version(), uca(UseCase.PRODUCT_CONFORMITY)).request();
    }

    private OnboardingRequest submit(OnboardingRequest draft) {
        return service.submit(draft.connectorId(), draft.id(), draft.version(), null);
    }

    @Test
    void aDraft_isCreatedForTheConnector_andReadBackByItOnly() {
        var created = service.create(ACME, data(ACME), null);

        var draft = created.request();
        assertThat(created.replayed()).isFalse();
        assertThat(draft.status()).isEqualTo(OnboardingStatus.DRAFT);
        assertThat(draft.connectorId()).isEqualTo(ACME);
        assertThat(draft.createdAt()).isEqualTo(NOW);
        assertThat(draft.businessId()).isEqualTo("DX-OR-000001");
        assertThat(service.get(ACME, draft.id())).isEqualTo(draft);
        problem(ProblemCode.NOT_FOUND, () -> service.get(OTHER, draft.id()));
        problem(ProblemCode.NOT_FOUND, () -> service.get(ACME, "unknown"));
        // nothing is announced, nor reviewed, before the submit
        assertThat(events.started).isEmpty();
        assertThat(reviewed).isEmpty();
    }

    @Test
    void theDid_isKeptTrimmedAndLowerCased() {
        var draft = service.create(ACME, data("  DID:Web:Acme.Example.com "), null).request();

        assertThat(draft.data().preferredDid()).contains("did:web:acme.example.com");
    }

    @Test
    void dataBeyondTheSchemasBounds_isRefused() {
        var refusal = problem(ProblemCode.INVALID_REQUEST,
                () -> service.create(ACME, withApplicantReference(data(ACME), "r".repeat(256)), null));

        assertThat(refusal.errors()).containsExactly(new FieldError("applicantReference", "at most 255 characters"));
        assertThat(repository.findByConnector(ACME)).isEmpty();
    }

    @Test
    void theSameContentWithoutAKey_returnsTheRequestItCreated_whateverBecameOfIt() {
        var original = submit(complete(ACME));

        var replay = service.create(ACME, data(ACME, UseCase.PRODUCT_CONFORMITY), null);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.request()).isEqualTo(original);
        // other content, or another connector, is a new request
        assertThat(service.create(ACME, data(ACME), null).replayed()).isFalse();
        assertThat(service.create(OTHER, data(ACME, UseCase.PRODUCT_CONFORMITY), null).replayed()).isFalse();
    }

    @Test
    void anIdempotencyKey_replaysItsCreate_andIsRefusedForOtherContent() {
        var original = service.create(ACME, data(ACME), "key-1").request();

        var replay = service.create(ACME, data(ACME), "key-1");
        var newKey = service.create(ACME, data(ACME), "key-2");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.request().id()).isEqualTo(original.id());
        // a new key starts a new request, even with the same content
        assertThat(newKey.replayed()).isFalse();
        problem(ProblemCode.IDEMPOTENCY_KEY_REUSED, () -> service.create(ACME, withGtc(data(ACME), "2.0"), "key-1"));
        // keys are the connector's own
        assertThat(service.create(OTHER, withGtc(data(ACME), "2.0"), "key-1").replayed()).isFalse();
    }

    @Test
    void aConnector_hasAtMost10Drafts() {
        IntStream.range(0, 10).forEach(i -> service.create(ACME, withApplicantReference(data(ACME), "ref-" + i), null));

        var refusal = problem(ProblemCode.CONNECTOR_DRAFT_LIMIT_REACHED,
                () -> service.create(ACME, withApplicantReference(data(ACME), "ref-10"), null));

        assertThat(refusal.detail()).contains("10 drafts");
        // a replay is no new draft, and another connector has its own
        assertThat(service.create(ACME, withApplicantReference(data(ACME), "ref-0"), null).replayed()).isTrue();
        assertThat(service.create(OTHER, data(OTHER), null).replayed()).isFalse();
    }

    @Test
    void businessIds_areNeverHandedOutTwice_evenAfterADelete() {
        var first = service.create(ACME, withApplicantReference(data(ACME), "ref-1"), null).request();
        service.delete(ACME, first.id(), first.version());

        var second = service.create(ACME, withApplicantReference(data(ACME), "ref-2"), null).request();

        assertThat(first.businessId()).isEqualTo("DX-OR-000001");
        assertThat(second.businessId()).isEqualTo("DX-OR-000002");
    }

    @Test
    void aCorrection_dropsTheSignedCopiesThatNoLongerMatch_withTheirFiles() {
        var draft = complete(ACME);
        var gtcId = draft.document(DocumentType.GTC, null).orElseThrow().documentId();

        var corrected = service.replace(ACME, draft.id(), draft.version(), withGtc(draft.data(), "2.0"));

        assertThat(corrected.version()).isEqualTo(draft.version() + 1);
        assertThat(corrected.documents()).extracting(document -> document.type()).containsExactly(DocumentType.UCA);
        assertThat(documents.load(gtcId)).isEmpty();
        problem(ProblemCode.VERSION_CONFLICT, () -> service.replace(ACME, draft.id(), draft.version(), draft.data()));
    }

    @Test
    void aDocument_isStoredWithItsDraft_replacedUnderItsId_andRemoved() {
        var draft = service.create(ACME, data(ACME), null).request();

        var added = service.addDocument(ACME, draft.id(), draft.version(), other("Insurance"));
        var documentId = added.document().documentId();
        var replaced = service.replaceDocument(ACME, draft.id(), documentId, added.request().version(), "Insurance 2027",
                pdfFile("insurance-2027.pdf"));
        var file = service.download(ACME, draft.id(), documentId);

        assertThat(replaced.document().documentId()).isEqualTo(documentId);
        assertThat(file.document().label()).isEqualTo("Insurance 2027");
        assertThat(file.content()).isEqualTo(pdfFile("insurance-2027.pdf").content());
        problem(ProblemCode.NOT_FOUND, () -> service.download(OTHER, draft.id(), documentId));

        var removed = service.removeDocument(ACME, draft.id(), documentId, replaced.request().version());
        assertThat(removed.documents()).isEmpty();
        assertThat(documents.load(documentId)).isEmpty();
        problem(ProblemCode.NOT_FOUND, () -> service.download(ACME, draft.id(), documentId));
    }

    @Test
    void aDeletedDraft_takesItsDocumentsWithIt_butASubmittedRequestCannotBeDeleted() {
        var draft = complete(ACME);
        var documentIds = draft.documents().stream().map(document -> document.documentId()).toList();

        service.delete(ACME, draft.id(), draft.version());

        problem(ProblemCode.NOT_FOUND, () -> service.get(ACME, draft.id()));
        documentIds.forEach(documentId -> assertThat(documents.load(documentId)).isEmpty());
        var submitted = submit(complete(OTHER));
        problem(ProblemCode.INVALID_ONBOARDING_REQUEST_STATE,
                () -> service.delete(OTHER, submitted.id(), submitted.version()));
    }

    @Test
    void aSubmit_filesTheRequest_announcesIt_andTakesItIntoReview_once() {
        var draft = complete(ACME);

        var submitted = service.submit(ACME, draft.id(), draft.version(), "submit-1");
        var again = service.submit(ACME, draft.id(), draft.version(), "submit-1");
        var withoutKey = service.submit(ACME, draft.id(), submitted.version(), null);

        assertThat(submitted.status()).isEqualTo(OnboardingStatus.SUBMITTED);
        assertThat(submitted.submittedAt()).isEqualTo(NOW);
        assertThat(submitted.version()).isEqualTo(draft.version() + 1);
        assertThat(again).isEqualTo(submitted);
        assertThat(withoutKey).isEqualTo(submitted);
        assertThat(events.started).containsExactly(new OnboardingStarted(draft.id(), "ref-1", ACME));
        assertThat(reviewed).containsExactly(draft.id());
        assertThat(resolved).containsExactly(ACME);
    }

    @Test
    void aSubmitKey_usedForAnotherRequest_isRefused() {
        var first = complete(ACME);
        service.submit(ACME, first.id(), first.version(), "submit-1");
        var second = service.create(ACME, withApplicantReference(data(ACME), "ref-2"), null).request();

        problem(ProblemCode.IDEMPOTENCY_KEY_REUSED, () -> service.submit(ACME, second.id(), second.version(), "submit-1"));
    }

    @Test
    void anIncompleteDraft_isNotSubmitted_andStaysAsItIs() {
        var draft = service.create(ACME, data(ACME, UseCase.PRODUCT_CONFORMITY), null).request();

        var refusal = problem(ProblemCode.ONBOARDING_REQUEST_SUBMIT_REJECTED, () -> submit(draft));

        assertThat(refusal.errors()).extracting(FieldError::field).containsExactly("documents", "ucas[0]");
        assertThat(service.get(ACME, draft.id())).isEqualTo(draft);
        assertThat(events.started).isEmpty();
        assertThat(reviewed).isEmpty();
    }

    @Test
    void aDidThatDoesNotResolve_isNotSubmitted_untilItDoes() {
        unresolvable.put(ACME, "no DID document could be fetched from http://acme.example.com/.well-known/did.json");
        var draft = complete(ACME);

        var refusal = problem(ProblemCode.ONBOARDING_REQUEST_SUBMIT_REJECTED, () -> submit(draft));

        assertThat(refusal.errors()).containsExactly(new FieldError("legalEntity.preferredDid", "does not resolve: "
                + "no DID document could be fetched from http://acme.example.com/.well-known/did.json"));
        assertThat(service.get(ACME, draft.id()).status()).isEqualTo(OnboardingStatus.DRAFT);
        unresolvable.clear();
        assertThat(submit(draft).status()).isEqualTo(OnboardingStatus.SUBMITTED);
    }

    @Test
    void aDidInUse_byAnotherRequestInProgressOrApproved_isNotSubmitted() {
        var first = submit(complete(ACME));
        // another connector submitting the same DID — in another spelling
        var second = service.create(OTHER, withApplicantReference(data(" DID:web:ACME.example.com"), "ref-2"), null)
                .request();
        var secondId = second.id();
        second = service.addDocument(OTHER, secondId, second.version(), gtc()).request();
        var draft = second;

        var refusal = problem(ProblemCode.ONBOARDING_REQUEST_SUBMIT_REJECTED, () -> submit(draft));

        assertThat(refusal.errors()).containsExactly(new FieldError("legalEntity.preferredDid", "already in use"));
        assertThat(refusal.detail()).doesNotContain(first.id());
        // a rejected request frees its DID
        repository.save(service.get(ACME, first.id()).rejected(NOW, null, "no", true));
        assertThat(submit(draft).status()).isEqualTo(OnboardingStatus.SUBMITTED);
    }

    @Test
    void aConnector_hasAtMost10RequestsAwaitingADecision() {
        IntStream.range(0, 10).forEach(i -> {
            var draft = service.create(ACME, withApplicantReference(data("did:web:acme-" + i + ".example.com"), "ref-" + i),
                    null).request();
            draft = service.addDocument(ACME, draft.id(), draft.version(), gtc()).request();
            submit(draft);
        });
        var eleventh = service.create(ACME, withApplicantReference(data("did:web:acme-10.example.com"), "ref-10"), null)
                .request();
        var draft = service.addDocument(ACME, eleventh.id(), eleventh.version(), gtc()).request();

        var refusal = problem(ProblemCode.CONNECTOR_PENDING_REQUEST_LIMIT_REACHED, () -> submit(draft));

        assertThat(refusal.errors()).isEmpty();
        assertThat(service.get(ACME, draft.id()).status()).isEqualTo(OnboardingStatus.DRAFT);
    }

    @Test
    void aDecadeXId_isKeptFromAParticipantHostedHere_only() {
        var external = service.create(ACME, declaring(data(ACME), "DX-12345678"), null).request();
        var hosted = service.create(HOSTED, declaring(data(HOSTED), "DX-87654321"), null).request();

        assertThat(external.data().declaredLegalEntityId()).isEmpty();
        assertThat(hosted.data().declaredLegalEntityId()).contains("DX-87654321");
        var refusal = problem(ProblemCode.INVALID_REQUEST,
                () -> service.create(HOSTED, declaring(data(HOSTED), "BPNL000000000001"), null));
        assertThat(refusal.errors()).extracting(FieldError::field).containsExactly("legalEntity.legalEntityId");
    }

    @Test
    void draftsUntouchedForTheTtl_areDeleted_withTheirDocuments() {
        var stale = service.create(ACME, withApplicantReference(data(ACME), "ref-1"), null).request();
        stale = service.addDocument(ACME, stale.id(), stale.version(), gtc()).request();
        var staleDocument = stale.documents().get(0).documentId();
        var submitted = submit(complete(OTHER));
        clock.advance(Duration.ofDays(20));
        var touched = service.create(ACME, withApplicantReference(data(ACME), "ref-2"), null).request();
        clock.advance(Duration.ofDays(11));

        var deleted = service.deleteDraftsUntouchedSince(clock.instant().minus(Duration.ofDays(30)));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById(stale.id())).isEmpty();
        assertThat(documents.load(staleDocument)).isEmpty();
        assertThat(repository.findById(touched.id())).isPresent();
        assertThat(repository.findById(submitted.id())).isPresent();
    }

    @Test
    void aDocument_isAddedOnTheCurrentVersionOnly() {
        var draft = service.create(ACME, data(ACME), null).request();
        var added = service.addDocument(ACME, draft.id(), draft.version(), gtc());

        problem(ProblemCode.VERSION_CONFLICT,
                () -> service.addDocument(ACME, draft.id(), draft.version(), other("Insurance")));
        problem(ProblemCode.VERSION_CONFLICT, () -> service.removeDocument(ACME, draft.id(),
                added.document().documentId(), draft.version()));
        problem(ProblemCode.NOT_FOUND, () -> service.removeDocument(ACME, draft.id(), UUID.randomUUID(),
                added.request().version()));
    }

    /** A clock the test moves on. */
    private static class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
