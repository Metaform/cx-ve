package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.adapter.out.document.InMemoryDocumentStore;
import com.metaform.dxonboarding.adapter.out.persistence.InMemoryOnboardingRequestRepository;
import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.InvalidOnboardingRequestException;
import com.metaform.dxonboarding.domain.model.onboarding.Address;
import com.metaform.dxonboarding.domain.model.onboarding.CompanyType;
import com.metaform.dxonboarding.domain.model.onboarding.ConsentDeclaration;
import com.metaform.dxonboarding.domain.model.onboarding.Declarations;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentRef;
import com.metaform.dxonboarding.domain.model.onboarding.LegalEntity;
import com.metaform.dxonboarding.domain.model.onboarding.LegalPerson;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.RegistrationNumber;
import com.metaform.dxonboarding.domain.model.onboarding.Submission;
import com.metaform.dxonboarding.domain.model.onboarding.SubmittedDocument;
import com.metaform.dxonboarding.domain.model.onboarding.UseCaseAgreement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Filing a submission: validated first, recognized on retry, scoped to the submitting connector. */
class DefaultOnboardingRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final UUID GTC_VERSION = UUID.fromString("0b8e1f0e-5c0a-4a52-9a53-7a1d2c3b4e5f");
    private static final Address ADDRESS = new Address("Beispielstrasse 1", "10115", "Berlin", "DE", "Germany");

    private final List<SubmittedDocument> stored = new ArrayList<>();
    private final InMemoryDocumentStore documents = new InMemoryDocumentStore() {
        @Override
        public DocumentRef store(SubmittedDocument document) {
            stored.add(document);
            return super.store(document);
        }
    };
    private final InMemoryOnboardingRequestRepository repository = new InMemoryOnboardingRequestRepository();
    /** The requests taken into review; nothing is approved here — AutomaticReviewTest covers that. */
    private final List<String> reviewed = new ArrayList<>();
    private final AutomaticReview review = new AutomaticReview(repository,
            new ReviewProperties(null, new ReviewProperties.AutoApprove(false, false), null), Runnable::run,
            Clock.fixed(NOW, ZoneOffset.UTC)) {
        @Override
        public void submitted(OnboardingRequest request) {
            reviewed.add(request.id());
            super.submitted(request);
        }
    };
    private final DefaultOnboardingRequestService service = new DefaultOnboardingRequestService(
            repository, documents, review, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Submission submission(String legalName) {
        var request = new OnboardingRequestData(
                new LegalEntity(null, legalName, "DE", CompanyType.LIMITED_LIABILITY_COMPANY, null, null, null,
                        List.of(new RegistrationNumber(RegistrationNumber.Scheme.vatID, "DE123456789")), ADDRESS),
                new LegalPerson("Erika Mustermann", "erika@example.org", null, null, null, null),
                List.of(),
                new ConsentDeclaration(GTC_VERSION, "1.0", true),
                List.of(new UseCaseAgreement("export-control", "Export Control", null, "v1.0", true)),
                new Declarations(true, true, true),
                "ref-1");
        return new Submission(request, List.of(
                new SubmittedDocument(Submission.GTC_DOCUMENT, "gtc.pdf", "application/pdf", "%PDF gtc".getBytes()),
                new SubmittedDocument("ucaDocument[export-control]", "uca.pdf", "application/pdf", "%PDF uca".getBytes())));
    }

    @Test
    void aSubmissionIsFiledWithItsDocumentsAndAwaitsReview() {
        var request = service.submit("connector-a", submission("Example GmbH"));

        assertThat(request.status()).isEqualTo(OnboardingStatus.SUBMITTED);
        assertThat(request.connectorId()).isEqualTo("connector-a");
        assertThat(request.submittedAt()).isEqualTo(NOW);
        assertThat(request.businessId()).isEqualTo("DX-OR-000001");
        assertThat(request.gtcDocument().filename()).isEqualTo("gtc.pdf");
        assertThat(request.ucaDocuments()).containsOnlyKeys("export-control");
        assertThat(request.registrationExtractDocument()).isNull();
        assertThat(request.decision()).isNull();
        assertThat(service.get("connector-a", request.id())).isEqualTo(request);
    }

    @Test
    void theSameContentAgainReturnsTheOriginalRequest() {
        var original = service.submit("connector-a", submission("Example GmbH"));

        var retry = service.submit("connector-a", submission("Example GmbH"));

        assertThat(retry).isEqualTo(original);
        assertThat(stored).hasSize(2);
        // a retry is the same request: it is not reviewed a second time
        assertThat(reviewed).containsExactly(original.id());
    }

    @Test
    void differentContentOrAnotherConnectorIsANewRequest() {
        var original = service.submit("connector-a", submission("Example GmbH"));

        var changed = service.submit("connector-a", submission("Example AG"));
        var otherConnector = service.submit("connector-b", submission("Example GmbH"));

        assertThat(changed.id()).isNotEqualTo(original.id());
        assertThat(otherConnector.id()).isNotEqualTo(original.id());
        assertThat(otherConnector.businessId()).isEqualTo("DX-OR-000003");
    }

    @Test
    void anIncompleteSubmissionIsRefusedAndNothingIsStored() {
        var incomplete = new Submission(submission("Example GmbH").request(), List.of());

        assertThatThrownBy(() -> service.submit("connector-a", incomplete))
                .isInstanceOfSatisfying(InvalidOnboardingRequestException.class, e -> assertThat(e.violations())
                        .contains("gtcDocument: required", "ucaDocument[export-control]: required for use case 'export-control'"));
        assertThat(stored).isEmpty();
    }

    @Test
    void anotherConnectorsRequestReadsLikeAnUnknownOne() {
        var request = service.submit("connector-a", submission("Example GmbH"));

        assertThatThrownBy(() -> service.get("connector-b", request.id())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.get("connector-a", "unknown")).isInstanceOf(NoSuchElementException.class);
    }
}
