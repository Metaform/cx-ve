package com.metaform.cxve.hub.adapter.out.cfm;

import com.metaform.cxve.hub.adapter.out.cfm.model.ParticipantProfile;
import com.metaform.cxve.hub.adapter.out.cfm.model.TenantCreationRequest;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Provisions a member's EDC resources through the CFM Tenant Manager: one tenant per member, one
 * participant profile deployed into it. Ported from the Onboarding API's former wallet
 * provisioning, minus everything the registration agent used to do — the VPA orchestration this
 * feeds no longer contains it.
 *
 * <p>The {@code cfm.issuer} VPA properties are STILL sent even though no registration activity
 * consumes them: the certo activity reads the member's BPN from exactly these properties.
 */
@Service
public class CfmTenantManager implements TenantManager {

    private static final Logger log = LoggerFactory.getLogger(CfmTenantManager.class);

    private final TenantManagerClient client;
    private final String dataplaneTransferType;
    private final String dataplaneEndpointType;
    private final String dataplaneEndpoint;
    private final String dataplaneTokenSource;
    private final String ccmTransferType;
    private final String ccmEndpointType;
    private final String ccmEndpoint;
    private final String ccmTokenSource;

    public CfmTenantManager(TenantManagerClient client,
                            @Value("${participant.dataplane.transfer-type:HttpData-PULL}") String dataplaneTransferType,
                            @Value("${participant.dataplane.endpoint-type:HTTP}") String dataplaneEndpointType,
                            @Value("${participant.dataplane.endpoint:}") String dataplaneEndpoint,
                            @Value("${participant.dataplane.token-source:provider}") String dataplaneTokenSource,
                            @Value("${participant.ccm.transfer-type:HttpData-PULL}") String ccmTransferType,
                            @Value("${participant.ccm.endpoint-type:HTTP}") String ccmEndpointType,
                            @Value("${participant.ccm.endpoint:}") String ccmEndpoint,
                            @Value("${participant.ccm.token-source:provider}") String ccmTokenSource) {
        this.client = client;
        this.dataplaneTransferType = dataplaneTransferType;
        this.dataplaneEndpointType = dataplaneEndpointType;
        this.dataplaneEndpoint = dataplaneEndpoint;
        this.dataplaneTokenSource = dataplaneTokenSource;
        this.ccmTransferType = ccmTransferType;
        this.ccmEndpointType = ccmEndpointType;
        this.ccmEndpoint = ccmEndpoint;
        this.ccmTokenSource = ccmTokenSource;
    }

    @Override
    public ProvisionedProfile deployParticipant(Membership membership, List<String> activeAgreementIds) {
        var tenant = client.createTenant(new TenantCreationRequest(Map.of("name", membership.name())));
        log.debug("Created tenant '{}' for membership '{}'", tenant.id(), membership.externalId());

        var profile = toParticipantProfile(membership, activeAgreementIds);
        log.info("Deploying participant profile for DID = {} (tenant {})", membership.did(), tenant.id());
        // The deploy response predates any provisioning progress — its id is what gets stored;
        // the profile's state is read through refresh() from then on.
        profile = client.deployParticipantProfile(tenant.id(), profile);
        return toResult(tenant.id(), profile);
    }

    @Override
    public ProvisionedProfile refresh(Membership membership) {
        var profile = client.getParticipantProfile(membership.tenantId(), membership.participantProfileId());
        return toResult(membership.tenantId(), profile);
    }

    private ProvisionedProfile toResult(String tenantId, ParticipantProfile profile) {
        return new ProvisionedProfile(tenantId, profile.getId(), profile.getParticipantContextId(), profile.isError());
    }

    private ParticipantProfile toParticipantProfile(Membership membership, List<String> activeAgreementIds) {
        var builder = ParticipantProfile.builder()
                .identifier(membership.did())
                .vpaProperty("cfm.issuer", holderProperties(membership, activeAgreementIds));
        // For each configured endpoint, the CFM siglet agent configures a transfer-type mapping in
        // Siglet and registers the participant's data-plane instance with the control plane — the
        // prerequisite for the participant's assets to carry catalog distributions. Without any,
        // provisioning still succeeds but no transfers are possible. The ccm mapping points at
        // Certo's protocol API, so CCM certificate exchanges (CX-0135) can ride on data flows.
        var transferTypeMappings = new HashMap<String, Object>();
        if (!dataplaneEndpoint.isBlank()) {
            transferTypeMappings.put(dataplaneTransferType,
                    transferTypeMapping(dataplaneTransferType, dataplaneEndpointType, dataplaneEndpoint, dataplaneTokenSource));
        }
        if (!ccmEndpoint.isBlank()) {
            transferTypeMappings.put(ccmTransferType,
                    transferTypeMapping(ccmTransferType, ccmEndpointType, ccmEndpoint, ccmTokenSource));
        }
        if (!transferTypeMappings.isEmpty()) {
            builder.vpaProperty("cfm.dataplane", Map.of("transferTypeMappings", transferTypeMappings));
        }
        return builder.build();
    }

    private Map<String, Object> transferTypeMapping(String transferType, String endpointType, String endpoint, String tokenSource) {
        return Map.of(
                "transferType", transferType,
                "endpointType", endpointType,
                "endpoint", endpoint,
                "tokenSource", tokenSource);
    }

    private Map<String, Object> holderProperties(Membership membership, List<String> activeAgreementIds) {
        return Map.of(
                "id", membership.did(),
                "contractVersion", "1.0.0",
                "memberOf", String.join(", ", activeAgreementIds),
                "bpn", membership.bpn()
        );
    }
}
