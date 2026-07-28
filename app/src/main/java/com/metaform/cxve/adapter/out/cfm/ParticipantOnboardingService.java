package com.metaform.cxve.adapter.out.cfm;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.util.Optional.ofNullable;

/**
 * Placeholder wallet provisioning. A real implementation would provision an operator-hosted wallet
 * or register a participant-owned one per CX-0149, based on the applicant's preference.
 */
@Service
public class ParticipantOnboardingService implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantOnboardingService.class);
    private final TenantManagerClient tenantManagerClient;
    private final String didTemplate;
    private final String dataplaneTransferType;
    private final String dataplaneEndpointType;
    private final String dataplaneEndpoint;
    private final String dataplaneTokenSource;

    public ParticipantOnboardingService(@Autowired TenantManagerClient tenantManagerClient,
                                        @Value("${participant.did.template:did:web:identity.cxve.localhost:}") String didTemplate,
                                        @Value("${participant.dataplane.transfer-type:HttpData-PULL}") String dataplaneTransferType,
                                        @Value("${participant.dataplane.endpoint-type:HTTP}") String dataplaneEndpointType,
                                        @Value("${participant.dataplane.endpoint:}") String dataplaneEndpoint,
                                        @Value("${participant.dataplane.token-source:provider}") String dataplaneTokenSource) {
        this.tenantManagerClient = tenantManagerClient;
        this.didTemplate = didTemplate;
        this.dataplaneTransferType = dataplaneTransferType;
        this.dataplaneEndpointType = dataplaneEndpointType;
        this.dataplaneEndpoint = dataplaneEndpoint;
        this.dataplaneTokenSource = dataplaneTokenSource;
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
        log.debug("Deploy participant profile");

        var profile = toParticipantProfile(dataspaceId, registrationData, process.bpn());

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
        // With a configured endpoint, the CFM siglet agent configures this transfer-type mapping in
        // Siglet and registers the participant's data-plane instance with the control plane — the
        // prerequisite for the participant's assets to carry catalog distributions. Without it,
        // provisioning still succeeds but no transfers are possible.
        if (!dataplaneEndpoint.isBlank()) {
            builder.vpaProperty("cfm.dataplane", Map.of(
                    "transferTypeMappings", Map.of(dataplaneTransferType, Map.of(
                            "transferType", dataplaneTransferType,
                            "endpointType", dataplaneEndpointType,
                            "endpoint", dataplaneEndpoint,
                            "tokenSource", dataplaneTokenSource))));
        }
        return builder.build();
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
        return ofNullable(registrationData.did()).orElseGet(() -> didTemplate + registrationData.shortName());
    }
}
