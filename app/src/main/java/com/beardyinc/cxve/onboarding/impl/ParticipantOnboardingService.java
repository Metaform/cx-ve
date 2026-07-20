package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.infrastructure.cfm.TenantManagerClient;
import com.beardyinc.cxve.infrastructure.cfm.model.Cell;
import com.beardyinc.cxve.infrastructure.cfm.model.DataspaceProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.ParticipantProfile;
import com.beardyinc.cxve.infrastructure.cfm.model.TenantCreationRequest;
import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.OnboardingProcess;
import com.beardyinc.cxve.onboarding.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Placeholder wallet provisioning. A real implementation would provision an operator-hosted wallet
 * or register a participant-owned one per CX-0149, based on the applicant's preference.
 */
@Service
public class ParticipantOnboardingService implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantOnboardingService.class);
    private final TenantManagerClient tenantManagerClient;

    public ParticipantOnboardingService(@Autowired TenantManagerClient tenantManagerClient) {
        this.tenantManagerClient = tenantManagerClient;
    }

    @Override
    public ParticipantProfile provisionWallet(OnboardingProcess process, PartnerRegistrationData registrationData) {
        var dataspaceId = tenantManagerClient.listDataspaceProfiles().stream().findFirst().map(DataspaceProfile::id).orElseThrow(() -> new RuntimeException("No dataspace profile found in CFM Tenant Manager"));
        var cellId = tenantManagerClient.listCells().stream().findFirst().map(Cell::id).orElseThrow(() -> new RuntimeException("No cell found in CFM Tenant Manager"));
        log.debug("Using dataspace ID {}", dataspaceId);
        log.debug("Provisioning participant for cell {}", cellId);
        log.debug("Creating tenant with name '{}'", registrationData.name());
        var tenant = tenantManagerClient.createTenant(new TenantCreationRequest(Map.of("name", registrationData.name())));
        log.debug("Created tenant with ID '{}'", tenant.id());
        log.debug("Deploy participant profile");

        var profile = toParticipantProfile(dataspaceId, registrationData);

        tenantManagerClient.deployParticipantProfile(tenant.id(), profile);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return tenantManagerClient.getParticipantProfile(tenant.id(), profile.getId());
    }

    private ParticipantProfile toParticipantProfile(String dataspaceId, PartnerRegistrationData registrationData) {
        var did = generateDid(registrationData);
        return ParticipantProfile.builder()
                .identifier(did)
                .participantRole(dataspaceId, List.of("member"))
                .vpaProperties(Map.of("cfm.issuer", holderProperties(did)))
                .build();
    }

    private Map<String, Object> holderProperties(String holderId) {
        var now = Instant.now().toString();
        return Map.of(
                "id", holderId,
                "membership", Map.of("since", now),
                "membershipType", "full-member",
                "membershipStartDate", now,
                "contractVersion", "1.0.0",
                "component_types", "all",
                "since", now
        );
    }

    private String generateDid(PartnerRegistrationData registrationData) {
        return "did:web:identityhub.edc-v.svc.cluster.local%%3A7083:%s".formatted(registrationData.shortName());
    }
}
