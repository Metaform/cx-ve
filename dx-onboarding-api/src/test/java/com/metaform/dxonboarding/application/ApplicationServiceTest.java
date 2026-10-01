package com.metaform.dxonboarding.application;

import com.metaform.dxonboarding.domain.Decision;
import com.metaform.dxonboarding.domain.MembershipApplication;
import com.metaform.dxonboarding.domain.Webhook;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stub's decision rule — approve, unless a live application already holds the member number or
 * DID — and its delivery to the submitter's webhook. Decisions run inline here.
 */
class ApplicationServiceTest {

    private static final Webhook WEBHOOK = new Webhook("http://hub/api/callbacks/decade-x/registration-status",
            null, null, null);

    private final List<Decision> delivered = new ArrayList<>();
    private final WebhookSender sender = new WebhookSender() {
        @Override
        public void send(Webhook webhook, Decision decision) {
            delivered.add(decision);
        }
    };
    private final ApplicationService service = new ApplicationService(sender, Runnable::run, Duration.ZERO);

    private static MembershipApplication application(String ref, String memberNumber, String did) {
        return new MembershipApplication(ref, "Acme Corp", memberNumber, did, "DE", "ops@acme.example");
    }

    @Test
    void aFreshApplicationIsApprovedAndDeliveredToTheWebhook() {
        service.registerWebhook("hub", WEBHOOK);

        var record = service.submit("hub", application("ref-1", "DX-00000001", "did:web:acme"));

        assertThat(delivered).containsExactly(Decision.approved("ref-1", record.applicationId()));
        assertThat(service.get("hub", record.applicationId()).decision().decision())
                .isEqualTo(Decision.Outcome.APPROVED);
    }

    @Test
    void anApplicationCollidingWithALiveOneIsRejected() {
        service.registerWebhook("hub", WEBHOOK);
        service.submit("hub", application("ref-1", "DX-00000001", "did:web:acme"));

        var sameNumber = service.submit("hub", application("ref-2", "DX-00000001", "did:web:other"));
        var sameDid = service.submit("hub", application("ref-3", "DX-00000002", "did:web:acme"));

        assertThat(delivered).extracting(Decision::applicationRef, Decision::decision).containsExactly(
                org.assertj.core.groups.Tuple.tuple("ref-1", Decision.Outcome.APPROVED),
                org.assertj.core.groups.Tuple.tuple("ref-2", Decision.Outcome.REJECTED),
                org.assertj.core.groups.Tuple.tuple("ref-3", Decision.Outcome.REJECTED));
        assertThat(service.get("hub", sameNumber.applicationId()).decision().reason()).contains("already held");
        assertThat(service.get("hub", sameDid.applicationId()).decision().decision()).isEqualTo(Decision.Outcome.REJECTED);
    }

    @Test
    void aRejectedApplicationDoesNotBlockARetry() {
        service.registerWebhook("hub", WEBHOOK);
        service.submit("hub", application("ref-1", "DX-00000001", "did:web:acme"));
        service.submit("hub", application("ref-2", "DX-00000001", "did:web:other"));

        // ref-2 was rejected; a third attempt under its DID collides only with nothing live
        service.submit("hub", application("ref-3", "DX-00000003", "did:web:other"));

        assertThat(delivered.get(2).decision()).isEqualTo(Decision.Outcome.APPROVED);
    }

    @Test
    void withoutAWebhookTheDecisionIsStillReadable() {
        var record = service.submit("hub", application("ref-1", "DX-00000001", "did:web:acme"));

        assertThat(delivered).isEmpty();
        assertThat(service.get("hub", record.applicationId()).decision()).isNotNull();
    }

    @Test
    void anotherSubmittersApplicationIsNotVisible() {
        var record = service.submit("hub", application("ref-1", "DX-00000001", "did:web:acme"));

        assertThatThrownBy(() -> service.get("someone-else", record.applicationId()))
                .isInstanceOf(NoSuchElementException.class);
    }
}
