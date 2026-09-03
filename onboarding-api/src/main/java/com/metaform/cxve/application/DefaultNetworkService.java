package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.FileUploadResponse;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.FileStorageService;
import org.springframework.stereotype.Service;

/**
 * Hands a submitted partner registration to the {@link OnboardingOrchestrator}, which drives the
 * CX-0006 onboarding sequence. The endpoint returns as soon as the process is created; progression
 * continues asynchronously. File uploads are announced up front against the
 * {@link FileStorageService} and referenced from the registration by their ids.
 */
@Service
public class DefaultNetworkService implements NetworkService {

    private final OnboardingOrchestrator orchestrator;
    private final FileStorageService fileStorageService;

    public DefaultNetworkService(OnboardingOrchestrator orchestrator, FileStorageService fileStorageService) {
        this.orchestrator = orchestrator;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public String registerPartner(String clientId, PartnerRegistrationData registrationData) {
        return orchestrator.start(clientId, registrationData);
    }

    @Override
    public FileUploadResponse initiateFileUpload(String fileName, String contentType) {
        return fileStorageService.initiateUpload(fileName, contentType);
    }
}
