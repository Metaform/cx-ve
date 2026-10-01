package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.CcmApi;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.metaform.cxve.verification.application.TestFixtures.DATASPACE;
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
        when(hub.findByMemberId(DATASPACE, VP_BPN)).thenReturn(List.of(
                // a dead earlier attempt under the same BPN must be skipped
                membership("vp-old", "REJECTED", null, null, null),
                membership("vp-1", "CREDENTIALS_OFFERED", "did:web:vp", "pctx-vp", "proc-vp")));

        var participant = service.ensure(DATASPACE);

        assertEquals("pctx-vp", participant.participantContextId());
        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(certo).awaitParticipantContext("pctx-vp");
        // the inbox declares the CX-0135 consumer API — what a pushing participant finds it by
        verify(management).upsertAsset("pctx-vp", "ccm-inbox-verification", CcmApi.consumer(TestFixtures.CCM_API));
        // the dataspace's own policies, under its policy context
        verify(management).createPolicyIdempotent("pctx-vp", "vui-ccm-access-policy", "access",
                TestFixtures.POLICY_CONTEXT, TestFixtures.ACCESS);
        verify(management).createPolicyIdempotent("pctx-vp", "vui-ccm-contract-policy", "use",
                TestFixtures.POLICY_CONTEXT, TestFixtures.CONTRACT);
        verify(management).createContractDefinitionIdempotent("pctx-vp", "vui-ccm-cd",
                "vui-ccm-access-policy", "vui-ccm-contract-policy");
    }

    @Test
    void ensure_onboardsWhenNoLiveMembershipExists() {
        when(hub.findByMemberId(DATASPACE, VP_BPN)).thenReturn(List.of());
        when(hub.onboard(DATASPACE, "Verification Participant", "verification-participant", VP_BPN, "DEVERIFY0001", null))
                .thenReturn(membership("vp-1", "SUBMITTED", null, null, null));
        when(hub.awaitProvisioned("vp-1"))
                .thenReturn(membership("vp-1", "PROVISIONED", "did:web:vp", "pctx-vp", "proc-vp"));
        when(hub.awaitCredentialsOffered("vp-1"))
                .thenReturn(membership("vp-1", "CREDENTIALS_OFFERED", "did:web:vp", "pctx-vp", "proc-vp"));

        var participant = service.ensure(DATASPACE);

        assertEquals("pctx-vp", participant.participantContextId());
        // the participant consumes every run's certificate offer, so it must hold its own
        // credentials before a run starts — provisioned is not enough
        verify(hub).awaitCredentialsOffered("vp-1");
    }

    @Test
    void ensure_awaitsAnInFlightMembershipInsteadOfOnboarding() {
        when(hub.findByMemberId(DATASPACE, VP_BPN)).thenReturn(List.of(
                membership("vp-1", "PROVISIONING", "did:web:vp", null, "proc-vp")));
        when(hub.awaitProvisioned("vp-1"))
                .thenReturn(membership("vp-1", "PROVISIONED", "did:web:vp", "pctx-vp", "proc-vp"));
        when(hub.awaitCredentialsOffered("vp-1"))
                .thenReturn(membership("vp-1", "CREDENTIALS_OFFERED", "did:web:vp", "pctx-vp", "proc-vp"));

        service.ensure(DATASPACE);

        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void ensure_cachesTheParticipantAcrossCalls() {
        when(hub.findByMemberId(DATASPACE, VP_BPN)).thenReturn(List.of(
                membership("vp-1", "CREDENTIALS_OFFERED", "did:web:vp", "pctx-vp", "proc-vp")));

        var first = service.ensure(DATASPACE);
        var second = service.ensure(DATASPACE);

        assertEquals(first, second);
        verify(hub, times(1)).findByMemberId(DATASPACE, VP_BPN);
        verify(hub, never()).get(any());
    }

    @Test
    void status_reportsWithoutSideEffects() {
        when(hub.findByMemberId(DATASPACE, VP_BPN)).thenReturn(List.of(
                membership("vp-1", "CREDENTIALS_OFFERED", "did:web:vp", "pctx-vp", "proc-vp")));

        var status = service.status(DATASPACE);

        assertTrue(status.exists());
        assertFalse(status.offerSeeded());
        assertEquals("vp-1", status.membership().externalId());
        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(hub, never()).awaitProvisioned(any());
        verifyNoInteractions(management, certo);
    }

    @Test
    void status_whenNothingExists() {
        when(hub.findByMemberId(DATASPACE, VP_BPN)).thenReturn(List.of());

        var status = service.status(DATASPACE);

        assertFalse(status.exists());
        assertNull(status.membership());
    }
}
