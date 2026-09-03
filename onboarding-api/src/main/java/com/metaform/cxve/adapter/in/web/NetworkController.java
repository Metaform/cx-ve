package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.domain.model.FileUploadRequest;
import com.metaform.cxve.domain.model.FileUploadResponse;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/administration/registration/network")
public class NetworkController {

    private final NetworkService networkService;

    public NetworkController(NetworkService networkService) {
        this.networkService = networkService;
    }

    /**
     * Registers a partner company on behalf of the calling client — the token identity (see
     * {@link TokenClientId}) is recorded on the onboarding process and its status callbacks are
     * routed to that client's registered callback. Payloads missing a required field (see
     * {@link PartnerRegistrationData}) are rejected with 400; such rejections are logged and the
     * exact error message is returned (see {@link InvalidRequestShapeHandler}).
     *
     * @return the ID of the onboarding process
     */
    @PostMapping("/partnerregistration")
    public String registerPartner(@Valid @RequestBody PartnerRegistrationData registrationData,
                                  @AuthenticationPrincipal Jwt token) {
        return networkService.registerPartner(TokenClientId.from(token), registrationData);
    }

    /**
     * Announces a file for a partner registration: assigns a file id and returns the presigned
     * URL the caller uploads the contents to. The id goes into the registration payload's
     * {@code fileIds}.
     */
    @PostMapping("/partnerregistration/fileupload")
    public FileUploadResponse uploadFile(@Valid @RequestBody FileUploadRequest uploadRequest) {
        return networkService.initiateFileUpload(uploadRequest.fileName(), uploadRequest.contentType());
    }
}
