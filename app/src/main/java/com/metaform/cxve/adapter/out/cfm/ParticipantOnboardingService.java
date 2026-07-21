package com.metaform.cxve.adapter.out.cfm;

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

    public ParticipantOnboardingService(@Autowired TenantManagerClient tenantManagerClient,
                                        @Value("${participant.did.template:did:web:identityhub.edc-v.svc.cluster.local%%3A7083:}") String didTemplate) {
        this.tenantManagerClient = tenantManagerClient;
        this.didTemplate = didTemplate;
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
        return ParticipantProfile.builder()
                .identifier(did)
//                .participantRole(dataspaceId, List.of("member"))
                .vpaProperties(Map.of("cfm.issuer", holderProperties(did, bpn)))
                .build();
    }

    private Map<String, Object> holderProperties(String holderId, String bpn) {
        var now = Instant.now().toString();
        return Map.of(
                "id", holderId,
                "contractVersion", "1.0.0",
                "memberOf", "yomama",
                "bpn", bpn
        );
    }

    private String generateDid(PartnerRegistrationData registrationData) {
        return ofNullable(registrationData.did()).orElseGet(() -> didTemplate + registrationData.shortName());
    }
}
