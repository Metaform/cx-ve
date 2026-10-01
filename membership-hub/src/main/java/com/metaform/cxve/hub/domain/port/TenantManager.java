package com.metaform.cxve.hub.domain.port;

import com.metaform.cxve.hub.domain.model.Membership;
import java.util.List;
import java.util.Map;

/**
 * The hub's view of the CFM Tenant Manager: deploys a participant's EDC resources (the VPA
 * orchestration — connector, IdentityHub, Siglet, Certo; the credential-holder registration is
 * NOT part of it, the dataspace's onboarding API does that afterwards, against the wallet this
 * deployment creates) and reports their provisioning state.
 */
public interface TenantManager {

    /**
     * Creates a tenant for the membership and deploys its participant profile. Provisioning is
     * asynchronous on the Tenant Manager's side, so the returned profile may not carry a
     * participant context id yet — {@link #refresh} picks it up later.
     */
    ProvisionedProfile deployParticipant(Membership membership, DeploymentSpec spec);

    /** Re-reads the profile's provisioning state. */
    ProvisionedProfile refresh(Membership membership);

    /**
     * The dataspace-dependent parts of a participant profile.
     *
     * @param issuerProperties  the {@code cfm.issuer} VPA properties (see
     *                          {@link DataspaceOnboarding#issuerProperties})
     * @param dataspaceProfiles the connector's DSP dataspace profiles, e.g. {@code cx-neptune}
     * @param memberIdClaim     how the data plane stamps the caller's member id into flow tokens
     */
    record DeploymentSpec(
            Map<String, Object> issuerProperties,
            List<String> dataspaceProfiles,
            MemberIdClaim memberIdClaim
    ) {
    }

    /**
     * Where a flow's member id comes from: the {@code claim} of the caller's credential of
     * {@code credentialType}, written to the flow token as {@code flowClaim}.
     */
    record MemberIdClaim(String credentialType, String claim, String flowClaim) {
    }

    /**
     * The provisioning state of a deployed participant profile. {@code participantContextId} is
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
