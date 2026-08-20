package com.metaform.cxve.adapter.out.cfm;

import com.metaform.cxve.application.ParticipantDidResolver;
import com.metaform.cxve.domain.model.AgreementConsentData;
import com.metaform.cxve.domain.model.ConsentStatusId;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.ProvisionedParticipant;
import com.metaform.cxve.domain.port.WalletService;
import com.metaform.cxve.adapter.out.cfm.model.Cell;
import com.metaform.cxve.adapter.out.cfm.model.DataspaceProfile;
import com.metaform.cxve.adapter.out.cfm.model.ParticipantProfile;
import com.metaform.cxve.adapter.out.cfm.model.TenantCreationRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Placeholder wallet provisioning. A real implementation would provision an operator-hosted wallet
 * or register a participant-owned one per CX-0149, based on the applicant's preference.
 */
@Service
public class ParticipantOnboardingService implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantOnboardingService.class);
    private final TenantManagerClient tenantManagerClient;
    private final ParticipantDidResolver didResolver;
    private final String dataplaneTransferType;
    private final String dataplaneEndpointType;
    private final String dataplaneEndpoint;
    private final String dataplaneTokenSource;
    private final String ccmTransferType;
    private final String ccmEndpointType;
    private final String ccmEndpoint;
    private final String ccmTokenSource;

    public ParticipantOnboardingService(@Autowired TenantManagerClient tenantManagerClient,
                                        @Autowired ParticipantDidResolver didResolver,
                                        @Value("${participant.dataplane.transfer-type:HttpData-PULL}") String dataplaneTransferType,
                                        @Value("${participant.dataplane.endpoint-type:HTTP}") String dataplaneEndpointType,
                                        @Value("${participant.dataplane.endpoint:}") String dataplaneEndpoint,
                                        @Value("${participant.dataplane.token-source:provider}") String dataplaneTokenSource,
                                        @Value("${participant.ccm.transfer-type:https://w3id.org/dspace-sig/profile/http-pull}") String ccmTransferType,
                                        @Value("${participant.ccm.endpoint-type:HTTP}") String ccmEndpointType,
                                        @Value("${participant.ccm.endpoint:}") String ccmEndpoint,
                                        @Value("${participant.ccm.token-source:provider}") String ccmTokenSource) {
        this.tenantManagerClient = tenantManagerClient;
        this.didResolver = didResolver;
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
    public ProvisionedParticipant provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
        var dataspaceId = tenantManagerClient.listDataspaceProfiles().stream().findFirst().map(DataspaceProfile::id).orElseThrow(() -> new RuntimeException("No dataspace profile found in CFM Tenant Manager"));
        var cellId = tenantManagerClient.listCells().stream().findFirst().map(Cell::id).orElseThrow(() -> new RuntimeException("No cell found in CFM Tenant Manager"));
        log.debug("Using dataspace ID {}", dataspaceId);
        log.debug("Provisioning participant for cell {}", cellId);
        log.debug("Creating tenant with name '{}'", registrationData.name());
        var tenant = tenantManagerClient.createTenant(new TenantCreationRequest(Map.of("name", registrationData.name())));
        log.debug("Created tenant with ID '{}'", tenant.id());

        var profile = toParticipantProfile(dataspaceId, registrationData, process.bpn());

        log.info("Deploying participant profile for BPN = {} and DID = {}", process.bpn(), profile.getIdentifier());

        profile = tenantManagerClient.deployParticipantProfile(tenant.id(), profile);
        profile = tenantManagerClient.getParticipantProfile(tenant.id(), profile.getId());
        profile.setTenantId(tenant.id());

        return toDomain(profile);
    }

    @Override
    public ProvisionedParticipant checkProvisionStatus(OnboardingProcess process) {
        var profile = tenantManagerClient.getParticipantProfile(process.tenantId(), process.participantProfileId());
        return toDomain(profile);
    }

    /**
     * Maps the CFM Tenant Manager's {@link ParticipantProfile} onto the domain-facing result.
     */
    private ProvisionedParticipant toDomain(ParticipantProfile profile) {
        return new ProvisionedParticipant(
                profile.getId(),
                profile.getIdentifier(),
                profile.getTenantId(),
                profile.getParticipantContextId(),
                profile.getHolderProcessId(),
                profile.isError());
    }

    private ParticipantProfile toParticipantProfile(String dataspaceId, PartnerRegistrationData registrationData, String bpn) {
        var did = generateDid(registrationData);
        var builder = ParticipantProfile.builder()
                .identifier(did)
//                .participantRole(dataspaceId, List.of("member"))
                .vpaProperty("cfm.issuer", holderProperties(did, bpn, registrationData.agreements()));
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
            builder.vpaProperty("cfm.dataplane", Map.of(
                    "authorization", Map.of("type", "oauth2_token_exchange"),
                    "transferTypeMappings", transferTypeMappings));
        }
        return builder.build();
    }

    private Map<String, Object> transferTypeMapping(String transferType, String endpointType, String endpoint, String tokenSource) {
        return Map.of(
                "transferType", transferType,
                "endpointType", endpointType,
                "endpoint", endpoint,
                "tokenSource", tokenSource,
                "claimMappings", List.of(
                        Map.of("from", "flow.claims.vc.withType('BpnCredential').claim('bpn')", "to", "bpn")
                ));
    }

    private Map<String, Object> holderProperties(String holderId, String bpn, List<AgreementConsentData> agreements) {
        var agr = agreements.stream()
                .filter(acd -> acd.consentStatus().equals(ConsentStatusId.ACTIVE))
                .map(AgreementConsentData::agreementId)
                .collect(Collectors.joining(", "));
        return Map.of(
                "id", holderId,
                "contractVersion", "1.0.0",
                "memberOf", agr,
                "bpn", bpn
        );
    }

    private String generateDid(PartnerRegistrationData registrationData) {
        return didResolver.resolve(registrationData);
    }
}
