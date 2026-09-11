package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.metaform.cxve.verification.application.TestFixtures.membership;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationParticipantServiceTest {

    private static final String VP_BPN = "BPNLVERIFY000001";

    @Mock
    private MembershipHubClient hub;
    @Mock
    private ManagementApiClient management;
    @Mock
    private CertoClient certo;

    private VerificationParticipantService service;

    @BeforeEach
    void setUp() {
        service = new VerificationParticipantService(hub, management, certo, TestFixtures.props(Map.of()));
    }

    @Test
    void ensure_adoptsAnExistingProvisionedMembershipAndSeedsTheOffer() {
        when(hub.findByBpn(VP_BPN)).thenReturn(List.of(
                // a dead earlier attempt under the same BPN must be skipped
                membership("vp-old", "REJECTED", null, null, null),
                membership("vp-1", "PROVISIONED", "did:web:vp", "pctx-vp", "proc-vp")));

        var participant = service.ensure();

        assertEquals("pctx-vp", participant.participantContextId());
        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString());
        verify(certo).awaitParticipantContext("pctx-vp");
        verify(management).createAssetIdempotent("pctx-vp", "ccm-inbox-verification", "http://certo-svc:8080");
        verify(management).createPolicyIdempotent("pctx-vp", "vui-ccm-access-policy", "access",
                List.of(ManagementApiClient.MEMBERSHIP_CONSTRAINT));
        verify(management).createPolicyIdempotent("pctx-vp", "vui-ccm-contract-policy", "use",
                List.of(ManagementApiClient.FRAMEWORK_AGREEMENT_CONSTRAINT,
                        ManagementApiClient.USAGE_PURPOSE_CONSTRAINT,
                        ManagementApiClient.DATA_USAGE_DEFINITION_CONSTRAINT));
        verify(management).createContractDefinitionIdempotent("pctx-vp", "vui-ccm-cd",
                "vui-ccm-access-policy", "vui-ccm-contract-policy");
    }

    @Test
    void ensure_onboardsWhenNoLiveMembershipExists() {
        when(hub.findByBpn(VP_BPN)).thenReturn(List.of());
        when(hub.onboard("Verification Participant", "verification-participant", VP_BPN, "DEVERIFY0001"))
                .thenReturn(membership("vp-1", "SUBMITTED", null, null, null));
        when(hub.awaitProvisioned("vp-1"))
                .thenReturn(membership("vp-1", "PROVISIONED", "did:web:vp", "pctx-vp", "proc-vp"));

        var participant = service.ensure();

        assertEquals("pctx-vp", participant.participantContextId());
    }

    @Test
    void ensure_awaitsAnInFlightMembershipInsteadOfOnboarding() {
        when(hub.findByBpn(VP_BPN)).thenReturn(List.of(
                membership("vp-1", "PROVISIONING", "did:web:vp", null, "proc-vp")));
        when(hub.awaitProvisioned("vp-1"))
                .thenReturn(membership("vp-1", "PROVISIONED", "did:web:vp", "pctx-vp", "proc-vp"));

        service.ensure();

        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ensure_cachesTheParticipantAcrossCalls() {
        when(hub.findByBpn(VP_BPN)).thenReturn(List.of(
                membership("vp-1", "PROVISIONED", "did:web:vp", "pctx-vp", "proc-vp")));

        var first = service.ensure();
        var second = service.ensure();

        assertEquals(first, second);
        verify(hub, times(1)).findByBpn(VP_BPN);
        verify(hub, never()).get(any());
    }

    @Test
    void status_reportsWithoutSideEffects() {
        when(hub.findByBpn(VP_BPN)).thenReturn(List.of(
                membership("vp-1", "PROVISIONED", "did:web:vp", "pctx-vp", "proc-vp")));

        var status = service.status();

        assertTrue(status.exists());
        assertFalse(status.offerSeeded());
        assertEquals("vp-1", status.membership().externalId());
        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString());
        verify(hub, never()).awaitProvisioned(any());
        verifyNoInteractions(management, certo);
    }

    @Test
    void status_whenNothingExists() {
        when(hub.findByBpn(VP_BPN)).thenReturn(List.of());

        var status = service.status();

        assertFalse(status.exists());
        assertNull(status.membership());
    }
}
