package com.metaform.cxve.hub.domain.port;

import com.metaform.cxve.hub.domain.model.Membership;
import java.util.List;

/**
 * The hub's view of the CFM Tenant Manager: deploys a participant's EDC resources (the VPA
 * orchestration — connector, IdentityHub, Siglet, Certo; the credential-holder registration is
 * NOT part of it, the Onboarding API has done that before the CONFIRMED callback fires) and
 * reports their provisioning state.
 */
public interface TenantManager {

    /**
     * Creates a tenant for the membership and deploys its participant profile. Provisioning is
     * asynchronous on the Tenant Manager's side, so the returned profile may not carry a
     * participant context id yet — {@link #refresh} picks it up later.
     */
    ProvisionedProfile deployParticipant(Membership membership, List<String> activeAgreementIds);

    /** Re-reads the profile's provisioning state. */
    ProvisionedProfile refresh(Membership membership);

    /**
     * The provisioning state of a deployed participant profile. {@code edcParticipantContextId} is
     * assigned asynchronously and null until the platform's provisioning has progressed far
     * enough; {@code error} reports a failed deployment.
     */
    record ProvisionedProfile(
            String tenantId,
            String participantProfileId,
            String participantContextId,
            boolean error
    ) {
    }
}
