package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.adapter.out.persistence.InMemoryOnboardingRequestRepository;
import com.metaform.dxonboarding.config.ReviewProperties;
import com.metaform.dxonboarding.domain.model.onboarding.Address;
import com.metaform.dxonboarding.domain.model.onboarding.CompanyType;
import com.metaform.dxonboarding.domain.model.onboarding.ConsentDeclaration;
import com.metaform.dxonboarding.domain.model.onboarding.DecadeXId;
import com.metaform.dxonboarding.domain.model.onboarding.Declarations;
import com.metaform.dxonboarding.domain.model.onboarding.LegalEntity;
import com.metaform.dxonboarding.domain.model.onboarding.LegalPerson;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.RegistrationNumber;
import com.metaform.dxonboarding.domain.model.onboarding.ReviewDecision;
import com.metaform.dxonboarding.domain.port.CredentialOfferService;
import com.metaform.dxonboarding.domain.port.HolderRegistrationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An approval makes the applicant a Decade-X member: it gets its Decade-X-ID (a participant hosted
 * here keeps the one it supplied, every other one is assigned one), is registered as credential
 * holder, and is offered its credentials — in that order, with the request's status telling how far
 * it got.
 */
class ApprovalServiceTest {

    private static final Instant DECIDED_AT = Instant.parse("2026-10-02T10:00:05Z");
    private static final String HOSTED_PREFIX = "did:web:identity.cxve.localhost:";
    private static final String HOSTED = HOSTED_PREFIX + "verification-participant-dx";
    private static final String EXTERNAL = "did:web:sut.example.com";
    private static final Address ADDRESS = new Address("Beispielstrasse 1", "10115", "Berlin", "DE", "Germany");

    private final InMemoryOnboardingRequestRepository repository = new InMemoryOnboardingRequestRepository();
    /** What happened, in order: "holder:<did>:<decadeXId>:<status while registering>", "offer:<did>". */
    private final List<String> calls = new ArrayList<>();
    private RuntimeException holderFailure;
    private RuntimeException offerFailure;

    private final HolderRegistrationService holders = request -> {
        if (holderFailure != null) {
            throw holderFailure;
        }
        var stored = repository.findById(request.id()).orElseThrow();
        calls.add("holder:%s:%s:%s".formatted(request.connectorId(), request.legalEntityId(), stored.status()));
    };
    private final CredentialOfferService offers = request -> {
        if (offerFailure != null) {
            throw offerFailure;
        }
        calls.add("offer:" + request.connectorId());
    };
    private final ApprovalService approval = new ApprovalService(repository, holders, offers,
            new ReviewProperties(HOSTED_PREFIX, null, null), Clock.fixed(DECIDED_AT, ZoneOffset.UTC));

    private OnboardingRequest filed(String connectorId, String suppliedDecadeXId) {
        var data = new OnboardingRequestData(
                new LegalEntity(connectorId, "Acme Corp", "DE", CompanyType.LIMITED_LIABILITY_COMPANY, null, null, null,
                        List.of(new RegistrationNumber(RegistrationNumber.Scheme.vatID, "DE123456789")), ADDRESS,
                        suppliedDecadeXId),
                new LegalPerson("Erika Mustermann", "erika@example.org", null, null, null, null),
                List.of(), new ConsentDeclaration(UUID.randomUUID(), "1.0", true), List.of(),
                new Declarations(true, true, true), "ref-1");
        var request = new OnboardingRequest(UUID.randomUUID().toString(), "DX-OR-000001", connectorId,
                Instant.parse("2026-10-02T10:00:00Z"), OnboardingStatus.SUBMITTED, data, null, null, null, null, null,
                Map.of(), null);
        repository.save(request);
        return request;
    }

    private OnboardingRequest stored(OnboardingRequest request) {
        return repository.findById(request.id()).orElseThrow();
    }

    @Test
    void aParticipantHostedHere_keepsItsDecadeXId_isRegisteredAndOffered() {
        var request = filed(HOSTED, "DX-99999999");

        approval.approve(request.id());

        // registered while APPROVAL_IN_PROGRESS — its Decade-X-ID already visible — and offered after
        assertThat(calls).containsExactly("holder:%s:DX-99999999:APPROVAL_IN_PROGRESS".formatted(HOSTED), "offer:" + HOSTED);
        var approved = stored(request);
        assertThat(approved.status()).isEqualTo(OnboardingStatus.APPROVED);
        assertThat(approved.legalEntityId()).isEqualTo("DX-99999999");
        assertThat(approved.decision()).isEqualTo(new ReviewDecision(DECIDED_AT, null, null, null));
    }

    @Test
    void anExternalParticipant_isAssignedADecadeXId_andCannotChooseItsOwn() {
        var unsupplied = filed(EXTERNAL, null);
        var supplied = filed("did:web:other.example.com", "DX-12345678");

        approval.approve(unsupplied.id());
        approval.approve(supplied.id());

        assertThat(stored(unsupplied).legalEntityId()).matches(DecadeXId.PATTERN);
        assertThat(stored(supplied).legalEntityId()).matches(DecadeXId.PATTERN).isNotEqualTo("DX-12345678");
        assertThat(stored(supplied).status()).isEqualTo(OnboardingStatus.APPROVED);
    }

    @Test
    void aParticipantHostedHere_withoutADecadeXId_isAssignedOne() {
        var request = filed(HOSTED, null);

        approval.approve(request.id());

        assertThat(stored(request).legalEntityId()).matches(DecadeXId.PATTERN);
    }

    @Test
    void aDecadeXIdAnotherParticipantHolds_rejectsTheRequest() {
        var holder = filed(HOSTED_PREFIX + "first", "DX-99999999");
        approval.approve(holder.id());
        calls.clear();

        var second = filed(HOSTED_PREFIX + "second", "DX-99999999");
        approval.approve(second.id());

        assertThat(calls).isEmpty();
        var rejected = stored(second);
        assertThat(rejected.status()).isEqualTo(OnboardingStatus.REJECTED);
        assertThat(rejected.decision().rejectReasonCode()).isEqualTo(ReviewDecision.RejectReasonCode.INVALID_LEGAL_ENTITY);
        assertThat(rejected.decision().rejectComment()).contains("DX-99999999");
    }

    @Test
    void theSameParticipant_mayRegisterItsDecadeXIdAgain() {
        approval.approve(filed(HOSTED, "DX-99999999").id());

        var again = filed(HOSTED, "DX-99999999");
        approval.approve(again.id());

        assertThat(stored(again).status()).isEqualTo(OnboardingStatus.APPROVED);
    }

    @Test
    void aFailingHolderRegistration_failsTheApproval_withoutAnOffer() {
        holderFailure = new IllegalStateException("IssuerService unreachable");
        var request = filed(HOSTED, "DX-99999999");

        approval.approve(request.id());

        assertThat(calls).isEmpty();
        var failed = stored(request);
        assertThat(failed.status()).isEqualTo(OnboardingStatus.APPROVAL_FAILED);
        assertThat(failed.decision().rejectComment()).isEqualTo("Credential issuance failed: IssuerService unreachable");
        // a failed approval does not hold the id: the participant may try again
        assertThat(repository.findHolderOfLegalEntityId("DX-99999999")).isEmpty();
    }

    @Test
    void aFailingOffer_failsTheApproval() {
        offerFailure = new IllegalStateException("holder DID does not resolve");
        var request = filed(EXTERNAL, null);

        approval.approve(request.id());

        assertThat(calls).hasSize(1).first().asString().startsWith("holder:");
        assertThat(stored(request).status()).isEqualTo(OnboardingStatus.APPROVAL_FAILED);
    }

    @Test
    void aRequestNoLongerAwaitingReview_isLeftAlone() {
        var request = filed(HOSTED, "DX-99999999");
        var rejected = request.rejected(DECIDED_AT, ReviewDecision.RejectReasonCode.OTHER, "no", false);
        repository.save(rejected);

        approval.approve(request.id());

        assertThat(calls).isEmpty();
        assertThat(stored(request)).isEqualTo(rejected);
    }
}
